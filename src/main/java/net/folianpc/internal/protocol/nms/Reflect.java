package net.folianpc.internal.protocol.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;

final class Reflect {

    private Reflect() {
    }

    static Class<?> nms(String subPackage, String mojangName, String spigotName) {
        Class<?> c = tryClass("net.minecraft." + subPackage + "." + mojangName);
        if (c == null && spigotName != null) {
            c = tryClass("net.minecraft." + subPackage + "." + spigotName);
        }
        if (c == null) {
            throw new IllegalStateException("Missing NMS class net.minecraft." + subPackage + "." + mojangName);
        }
        return c;
    }

    static Class<?> tryClass(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    static Object handle(Object craftObject) {
        try {
            Method m = craftObject.getClass().getMethod("getHandle");
            m.setAccessible(true);
            return m.invoke(craftObject);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("getHandle() failed on " + craftObject.getClass(), e);
        }
    }

    static Field field(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + owner.getName());
    }

    static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new IllegalStateException("No field of type " + type.getName() + " on " + owner.getName());
    }

    static Field fieldAssignableFrom(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != Object.class && f.getType().isAssignableFrom(type)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new IllegalStateException("No field assignable from " + type.getName() + " on " + owner.getName());
    }

    static Object staticField(Class<?> owner, String name) {
        try {
            return field(owner, name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read static " + owner.getSimpleName() + "." + name, e);
        }
    }

    static Object staticFieldByNameOrType(Class<?> owner, String name, Class<?> type) {
        try {
            return staticField(owner, name);
        } catch (IllegalStateException byName) {
            return staticFieldOfType(owner, type);
        }
    }

    static Object staticFieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            Field match = null;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                    if (match != null) {
                        match = null;
                        break;
                    }
                    match = f;
                }
            }
            if (match != null) {
                match.setAccessible(true);
                try {
                    return match.get(null);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read " + match, e);
                }
            }
        }
        throw new IllegalStateException("No unique static field of type " + type.getName() + " on " + owner.getName());
    }

    static Object staticFieldByNameOrGenericType(Class<?> owner, String name, Class<?> rawType, Class<?> typeArg) {
        try {
            return staticField(owner, name);
        } catch (IllegalStateException byName) {
            return staticFieldOfGenericType(owner, rawType, typeArg);
        }
    }

    static Object staticFieldOfGenericType(Class<?> owner, Class<?> rawType, Class<?> typeArg) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != rawType) {
                    continue;
                }
                Type generic = f.getGenericType();
                if (generic instanceof ParameterizedType pt) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length == 1 && args[0] == typeArg) {
                        f.setAccessible(true);
                        try {
                            return f.get(null);
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException("Cannot read " + f, e);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No static field of type " + rawType.getSimpleName()
                + "<" + typeArg.getSimpleName() + "> on " + owner.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }

    static Object get(Field field, Object instance) {
        try {
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }

    static void set(Field field, Object instance, Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot set " + field, e);
        }
    }

    static Method methodReturning(Class<?> owner, Class<?> returnType) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == returnType) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalStateException("No no-arg method returning " + returnType.getName() + " on " + owner.getName());
    }

    static Method methodByNameOrSignature(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
        try {
            return method(owner, name, params);
        } catch (IllegalStateException byName) {
            return methodOfSignature(owner, returnType, params);
        }
    }

    static Method methodOfSignature(Class<?> owner, Class<?> returnType, Class<?>... paramTypes) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() == returnType && Arrays.equals(m.getParameterTypes(), paramTypes)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalStateException("No method returning " + returnType.getName()
                + " with params " + Arrays.toString(paramTypes) + " on " + owner.getName());
    }

    static Method method(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("No method '" + name + "' on " + owner.getName());
    }

    static Method methodTaking(Class<?> owner, Class<?> paramType) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == paramType) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(paramType) && p[0] != Object.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalStateException("No single-arg method taking " + paramType.getName() + " on " + owner.getName());
    }

    static Constructor<?> constructor(Class<?> owner, Class<?>... params) {
        try {
            Constructor<?> ctor = owner.getDeclaredConstructor(params);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No matching constructor on " + owner.getName(), e);
        }
    }

    static Object newInstance(Constructor<?> ctor, Object... args) {
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Constructor failed: " + ctor, e);
        }
    }

    static Object invoke(Method method, Object instance, Object... args) {
        try {
            return method.invoke(instance, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Invoke failed: " + method, e);
        }
    }

    static Object allocate(Class<?> clazz) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, clazz);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot allocate " + clazz.getName(), e);
        }
    }
}
