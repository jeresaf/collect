/*
 * Copyright 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.odk.collect.android.login;

import org.odk.collect.android.openrosa.OpenRosaLoginSource;
import org.odk.collect.android.utilities.WebCredentialsUtils;

import timber.log.Timber;

public class LoginDetailsFetcher {

    private final LoginSource loginSource;

    public LoginDetailsFetcher(LoginSource loginSource) {
        this.loginSource = loginSource;
    }

    public void updateLoginPath(String url) {
        ((OpenRosaLoginSource) loginSource).updateLoginPath(url);
    }

    public void updateCredentials(WebCredentialsUtils webCredentialsUtils) {
        ((OpenRosaLoginSource) loginSource).updateWebCredentialsUtils(webCredentialsUtils);
    }

    public LoginDetails fetchUserDetails(String username, String password) throws LoginSourceException {
        Timber.e("Server User Detail Fetcher");
        return ((OpenRosaLoginSource) loginSource).fetchUser(username, password);
    }
}
