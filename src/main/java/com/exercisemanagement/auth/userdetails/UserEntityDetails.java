package com.exercisemanagement.auth.userdetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.exercisemanagement.auth.entity.AuthenticationEntity;

public class UserEntityDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final AuthenticationEntity authenticationEntity;

    public UserEntityDetails(AuthenticationEntity authenticationEntity) {
        this.authenticationEntity = authenticationEntity;
    }

    public String getUserId() {
        return authenticationEntity.getUserid();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : authenticationEntity.getRoles()) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return authenticationEntity.getPassword();
    }

    @Override
    public String getUsername() {
        return authenticationEntity.getUsername();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
