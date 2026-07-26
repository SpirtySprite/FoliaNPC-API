package net.folianpc.internal.protocol.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.function.Predicate;

final class Channels {

    private static final String VANILLA_HANDLER = "packet_handler";

    private final Class<?> playerConnectionClass;
    private final Class<?> networkManagerClass;

    private Field connectionField;
    private Field networkManagerField;
    private Field channelField;

    Channels() {
        playerConnectionClass = Reflect.nms("server.network", "ServerGamePacketListenerImpl", "PlayerConnection");
        networkManagerClass = Reflect.nms("network", "Connection", "NetworkManager");
    }

    Object connection(Player player) {
        Object entityPlayer = Reflect.handle(player);
        if (connectionField == null) {
            connectionField = Reflect.fieldAssignableFrom(entityPlayer.getClass(), playerConnectionClass);
        }
        return Reflect.get(connectionField, entityPlayer);
    }

    Channel channel(Player player) {
        Object connection = connection(player);
        if (networkManagerField == null) {
            networkManagerField = Reflect.fieldAssignableFrom(connection.getClass(), networkManagerClass);
        }
        Object networkManager = Reflect.get(networkManagerField, connection);
        if (channelField == null) {
            channelField = Reflect.fieldOfType(networkManager.getClass(), Channel.class);
        }
        return (Channel) Reflect.get(channelField, networkManager);
    }

    void inject(Player player, String handlerName, Predicate<Object> consumer) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(handlerName) != null) {
                    channel.pipeline().remove(handlerName);
                }
                ChannelDuplexHandler handler = new ChannelDuplexHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        boolean consumed;
                        try {
                            consumed = consumer.test(msg);
                        } catch (Throwable t) {
                            consumed = false;
                        }
                        if (!consumed) {
                            super.channelRead(ctx, msg);
                        }
                    }
                };
                if (channel.pipeline().get(VANILLA_HANDLER) != null) {
                    channel.pipeline().addBefore(VANILLA_HANDLER, handlerName, handler);
                } else {
                    channel.pipeline().addLast(handlerName, handler);
                }
            } catch (RuntimeException ignored) {
            }
        });
    }

    void eject(Player player, String handlerName) {
        Channel channel;
        try {
            channel = channel(player);
        } catch (RuntimeException e) {
            return;
        }
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(handlerName) != null) {
                    channel.pipeline().remove(handlerName);
                }
            } catch (RuntimeException ignored) {
            }
        });
    }
}
