package org.odk.collect.android.login;

public interface LoginSource {
    LoginDetails fetchUser(String username, String password) throws LoginSourceException;
}
