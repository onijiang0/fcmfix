package com.kooritea.fcmfix.libxposed;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import io.github.libxposed.api.XposedInterface;

public final class XposedBridge {

    private static XposedInterface xposedInterface;
    private static final Map<Member, List<XC_MethodHook>> HOOKS = new ConcurrentHashMap<>();

    private XposedBridge() {
    }

    public static void init(XposedInterface xposed) {
        xposedInterface = xposed;
    }

    public static void log(String text) {
        android.util.Log.i("fcmfix", text);
    }

    public static SharedPreferences getRemotePreferences(String group) {
        ensureInit();
        return xposedInterface.getRemotePreferences(group);
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        ensureInit();
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }

        HOOKS.computeIfAbsent(member, k -> new ArrayList<>()).add(callback);

        XposedInterface.HookHandle handle = xposedInterface.hook((java.lang.reflect.Executable) member)
                .intercept(chain -> {
                    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                    param.method = (Member) chain.getExecutable();
                    param.thisObject = chain.getThisObject();
                    param.args = chain.getArgs().toArray(new Object[0]);

                    List<XC_MethodHook> callbacks = HOOKS.get(member);
                    if (callbacks == null || callbacks.isEmpty()) {
                        return chain.proceed(param.args);
                    }

                    int beforeCount = 0;
                    for (XC_MethodHook hook : callbacks) {
                        hook.beforeHookedMethod(param);
                        beforeCount++;
                        if (param.isReturnEarly()) {
                            break;
                        }
                    }

                    if (!param.isReturnEarly()) {
                        try {
                            param.setResult(chain.proceed(param.args));
                        } catch (Throwable t) {
                            param.setThrowable(t);
                        }
                        param.resetReturnEarly();
                    }

                    for (int i = beforeCount - 1; i >= 0; i--) {
                        callbacks.get(i).afterHookedMethod(param);
                    }

                    if (param.hasThrowable()) {
                        throw param.getThrowable();
                    }
                    return param.getResult();
                });

        return callback.new Unhook(new HookHandleWrapper(member, callback, handle));
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        ensureInit();
        if (method instanceof Method) {
            Method m = (Method) method;
            XposedInterface.Invoker<?, Method> invoker = xposedInterface.getInvoker(m);
            invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
            try {
                return invoker.invoke(thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        if (method instanceof Constructor<?>) {
            Constructor<?> c = (Constructor<?>) method;
            XposedInterface.CtorInvoker<?> invoker = xposedInterface.getInvoker(c);
            try {
                return invoker.newInstance(args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        throw new IllegalArgumentException("Unsupported member type: " + method);
    }

    /**
     * 去优化（反内联）：短方法被调用方内联后 hook 不会触发。
     * 通过反射调用，兼容不同 libxposed API 版本。
     */
    public static boolean deoptimize(Member member) {
        ensureInit();
        try {
            Method m = xposedInterface.getClass().getMethod("deoptimize", java.lang.reflect.Executable.class);
            Object result = m.invoke(xposedInterface, (java.lang.reflect.Executable) member);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (Throwable e) {
            log("deoptimize 不可用: " + e.getMessage());
            return false;
        }
    }

    static final class HookHandleWrapper {
        private final Member member;
        private final XC_MethodHook callback;
        private final XposedInterface.HookHandle handle;

        HookHandleWrapper(Member member, XC_MethodHook callback, XposedInterface.HookHandle handle) {
            this.member = member;
            this.callback = callback;
            this.handle = handle;
        }

        void unhook() {
            List<XC_MethodHook> list = HOOKS.get(member);
            if (list != null) {
                list.remove(callback);
            }
            handle.unhook();
        }
    }

    private static void ensureInit() {
        if (xposedInterface == null) {
            throw new IllegalStateException("XposedBridge not initialized");
        }
    }
}
