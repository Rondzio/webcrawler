package com.udacity.webcrawler.profiler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A method interceptor that checks whether {@link Method}s are annotated with the {@link Profiled}
 * annotation. If they are, the method interceptor records how long the method invocation took.
 */
final class ProfilingMethodInterceptor implements InvocationHandler {

    private final Clock clock;
    private final Object invoked;
    private final ProfilingState state;

    ProfilingMethodInterceptor(Clock clock, Object invoked, ProfilingState state) {
        this.clock = Objects.requireNonNull(clock);
        this.invoked = invoked;
        this.state = state;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object invoked;
        boolean profiled = method.isAnnotationPresent(Profiled.class);
        if (profiled) {
            Instant start = clock.instant();
            try {
                invoked = method.invoke(this.invoked, args);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }finally {
                state.record(this.invoked.getClass(), method, Duration.between(start, clock.instant()));

            }
            return invoked;
        }
        return method.invoke(this.invoked, args);
    }
}
