package net.folianpc.internal;

import net.folianpc.api.Actions;
import net.folianpc.api.ClickType;
import net.folianpc.api.Npc;
import net.folianpc.api.NpcAction;
import net.folianpc.api.NpcClickContext;
import net.folianpc.api.Text;
import net.folianpc.internal.scheduler.Schedulers;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpgradeTest {

    private RecordingProtocolBackend backend;
    private PlayerTracker tracker;
    private NpcManager manager;

    @BeforeEach
    void setUp() {
        Schedulers.setSynchronousForTesting(true);
        backend = new RecordingProtocolBackend();
        tracker = new PlayerTracker();
        manager = new NpcManager(mock(Plugin.class), backend, tracker);
        manager.events(event -> { });
    }

    @AfterEach
    void tearDown() {
        Schedulers.setSynchronousForTesting(false);
    }

    private Player player(UUID id, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @Test
    void visibleWhenFiltersPlayersInRange() {
        NpcImpl npc = manager.create("Guard", new Position("world", 0, 64, 0, 0, 0));
        Player staff = player(UUID.randomUUID(), "Staff");
        Player member = player(UUID.randomUUID(), "Member");
        when(staff.hasPermission("staff")).thenReturn(true);
        tracker.put(new PlayerTracker.Tracked(staff, "world", 0, 64, 5));
        tracker.put(new PlayerTracker.Tracked(member, "world", 0, 64, 5));
        npc.visibleWhen(p -> p.hasPermission("staff"));

        manager.tick();

        assertTrue(npc.viewers().contains(staff.getUniqueId()));
        assertFalse(npc.viewers().contains(member.getUniqueId()));

        npc.visibleWhen(null);
        manager.tick();
        assertTrue(npc.viewers().contains(member.getUniqueId()));
    }

    @Test
    void forcedVisibilityWinsOverTheCondition() {
        NpcImpl npc = manager.create("Guard", new Position("world", 0, 64, 0, 0, 0));
        Player member = player(UUID.randomUUID(), "Member");
        tracker.put(new PlayerTracker.Tracked(member, "world", 0, 64, 5));
        npc.visibleWhen(p -> false);
        npc.showTo(member.getUniqueId());

        manager.tick();

        assertTrue(npc.viewers().contains(member.getUniqueId()));
    }

    @Test
    void failingConditionHidesInsteadOfBreakingTheTick() {
        NpcImpl npc = manager.create("Guard", new Position("world", 0, 64, 0, 0, 0));
        NpcImpl other = manager.create("Other", new Position("world", 0, 64, 0, 0, 0));
        Player member = player(UUID.randomUUID(), "Member");
        tracker.put(new PlayerTracker.Tracked(member, "world", 0, 64, 5));
        npc.visibleWhen(p -> {
            throw new IllegalStateException("boom");
        });

        manager.tick();

        assertFalse(npc.viewers().contains(member.getUniqueId()));
        assertTrue(other.viewers().contains(member.getUniqueId()));
    }

    @Test
    void playersInOtherWorldsAreIgnored() {
        NpcImpl npc = manager.create("Guard", new Position("world", 0, 64, 0, 0, 0));
        Player nether = player(UUID.randomUUID(), "Nether");
        tracker.put(new PlayerTracker.Tracked(nether, "world_nether", 0, 64, 1));

        manager.tick();

        assertTrue(npc.viewers().isEmpty());
    }

    @Test
    void cooldownActionBlocksPerPlayerAndCancelsTheRest() {
        AtomicInteger runs = new AtomicInteger();
        NpcAction guarded = Actions.cooldown(Duration.ofMinutes(1), ctx -> runs.incrementAndGet(), null);
        FakeContext first = new FakeContext(player(UUID.randomUUID(), "A"));
        FakeContext again = new FakeContext(first.player());
        FakeContext other = new FakeContext(player(UUID.randomUUID(), "B"));

        guarded.run(first);
        guarded.run(again);
        guarded.run(other);

        assertEquals(2, runs.get());
        assertFalse(first.remainingCancelled());
        assertTrue(again.remainingCancelled());
    }

    @Test
    void chanceRandomOnceAndSequenceBehave() {
        AtomicInteger runs = new AtomicInteger();
        FakeContext ctx = new FakeContext(player(UUID.randomUUID(), "A"));
        Actions.chance(0.0D, c -> runs.incrementAndGet()).run(ctx);
        Actions.chance(1.0D, c -> runs.incrementAndGet()).run(ctx);
        assertEquals(1, runs.get());

        Actions.random(c -> runs.addAndGet(10), c -> runs.addAndGet(10)).run(ctx);
        assertEquals(11, runs.get());

        NpcAction once = Actions.oncePerPlayer(c -> runs.incrementAndGet());
        once.run(ctx);
        once.run(ctx);
        assertEquals(12, runs.get());

        Actions.sequence(c -> runs.incrementAndGet(), NpcClickContext::cancelRemaining, c -> runs.incrementAndGet()).run(ctx);
        assertEquals(13, runs.get());
    }

    @Test
    void requireSneakingCancelsWhenStanding() {
        FakeContext ctx = new FakeContext(player(UUID.randomUUID(), "A"));
        Actions.requireSneaking().run(ctx);
        assertTrue(ctx.remainingCancelled());
    }

    @Test
    void textParsesLegacyAndMiniMessageTogether() {
        assertEquals("Gold Blue", Text.plain(Text.parse("&6Gold <blue>Blue")));
        assertEquals("Hex", Text.plain(Text.parse("&#ff00aaHex")));
        assertEquals("\\<red>", Text.escape("<red>"));
    }

    @Test
    void builtinPlaceholdersResolveThePlayerName() {
        Player viewer = player(UUID.randomUUID(), "Kiru");
        assertEquals("Hello Kiru", net.folianpc.api.Placeholders.apply(viewer, "Hello %player%"));
        assertEquals("No tokens", net.folianpc.api.Placeholders.apply(viewer, "No tokens"));
    }

    private static final class FakeContext implements NpcClickContext {
        private final Player player;
        private final Map<String, Object> data = new HashMap<>();
        private boolean cancelled;

        FakeContext(Player player) {
            this.player = player;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public Npc npc() {
            return null;
        }

        @Override
        public ClickType click() {
            return ClickType.RIGHT;
        }

        @Override
        public boolean sneaking() {
            return false;
        }

        @Override
        public Plugin plugin() {
            return null;
        }

        @Override
        public void cancelRemaining() {
            cancelled = true;
        }

        @Override
        public boolean remainingCancelled() {
            return cancelled;
        }

        @Override
        public Map<String, Object> data() {
            return data;
        }

        @Override
        public void run(Runnable task) {
            task.run();
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }
    }
}
