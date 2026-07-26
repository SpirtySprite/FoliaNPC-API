package net.folianpc.internal.protocol.nms;

final class Nms {

    private Nms() {
    }

    static Class<?> nested(Class<?> outer, String mojang, String spigot) {
        Class<?> found = Reflect.tryClass(outer.getName() + "$" + mojang);
        if (found == null) {
            found = Reflect.tryClass(outer.getName() + "$" + spigot);
        }
        if (found == null) {
            throw new IllegalStateException("Missing nested class " + outer.getName() + "$" + mojang);
        }
        return found;
    }

    static byte angle(float degrees) {
        return net.folianpc.internal.geometry.LookAt.angleByte(degrees);
    }
}
