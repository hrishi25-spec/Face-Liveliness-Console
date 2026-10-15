package io.mosip.liveness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the caller's address once per request and publishes it as
 * {@link ClientIpResolver#ATTRIBUTE}, so controllers can attribute an action to
 * a host without re-deriving it, and without the audit trail and the rate
 * limiter being able to disagree about who the caller was.
 *
 * <p>Runs just after {@link SecurityHeadersFilter} ({code HIGHEST_PRECEDENCE + 10})
 * so a 413 rejection still carries the security headers, and long before the
 * dispatcher, so a handler never sees an unattributed request.</p>
 *
 * <p>Applies to every method, including the open config <b>reads</b>: recording
 * where a request came from adds no new information to the response and gates
 * nothing. Only the admin-key check on the write path decides who may change a
 * policy, and the address is recorded after that check has already passed, so
 * it never becomes an authentication input.</p>
 *
 * <p>ponytail: the resolver is optional on purpose, for the same reason as
 * {@link RateLimitFilter}'s a {@code @WebMvcTest} slice instantiates every
 * {@code Filter} bean but not ordinary components, and a required dependency
 * would fail those contexts for no reason. The fallback trusts nobody, which
 * is the shipped default.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientIpFilter extends OncePerRequestFilter {

    private final ClientIpResolver resolver;

    /**
     * Explicitly marked: with a second, package-private constructor present,
     * Spring has no single candidate to autowire and falls back to looking for
     * a default one that does not exist, which fails every {@code @WebMvcTest}
     * slice. Same shape as {@link RateLimitFilter}.
     */
    @Autowired
    public ClientIpFilter(ObjectProvider<ClientIpResolver> resolverProvider) {
        this(resolverProvider.getIfAvailable(ClientIpResolver::new));
    }

    ClientIpFilter(ClientIpResolver resolver) {
        this.resolver = resolver != null ? resolver : new ClientIpResolver();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        request.setAttribute(ClientIpResolver.ATTRIBUTE, resolver.resolve(request));
        filterChain.doFilter(request, response);
    }
}
