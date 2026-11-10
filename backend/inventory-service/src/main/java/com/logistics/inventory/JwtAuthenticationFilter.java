package com.logistics.inventory;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.authority.SimpleGrantedAuthority;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
@Component public class JwtAuthenticationFilter extends OncePerRequestFilter {private final JwtTokenService tokens; public JwtAuthenticationFilter(JwtTokenService t){tokens=t;} protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {String h=req.getHeader("Authorization");if(h!=null&&h.startsWith("Bearer ")){try{var c=tokens.parse(h.substring(7));var role=String.valueOf(c.get("role",String.class));var auth=new UsernamePasswordAuthenticationToken(c.getSubject(),null,java.util.List.of(new SimpleGrantedAuthority("ROLE_"+role)));SecurityContextHolder.getContext().setAuthentication(auth);}catch(JwtException|IllegalArgumentException ex){res.sendError(401,"Invalid or expired token");return;}}chain.doFilter(req,res);}}



