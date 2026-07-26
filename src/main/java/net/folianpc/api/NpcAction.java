package net.folianpc.api;

import java.util.function.Predicate;

// ctx carries the click details plus Folia-safe ways to schedule work.
@FunctionalInterface
public interface NpcAction {

    void run(NpcClickContext ctx);

    default NpcAction when(Predicate<NpcClickContext> condition) {
        return ctx -> {
            if (condition.test(ctx)) {
                run(ctx);
            }
        };
    }

    default NpcAction then(NpcAction next) {
        return ctx -> {
            run(ctx);
            next.run(ctx);
        };
    }
}
