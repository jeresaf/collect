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

package org.odk.collect.android.openrosa;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

public interface OpenRosaUserHttpInterface {

    /**
     * Fetches user details from the server
     *
     * @param username       The username of the user
     * @param password       The password of the user
     * @param uri            where to fetch user details from
     * @param contentLength  contentLength requested by the server
     * @return ResponseMessageParser object that contains the response XML
     * @throws IOException can be thrown if files do not exist
     */
    @NonNull
    HttpGetResult login(@NonNull String username,
                         @NonNull String password,
                         @NonNull URI uri,
                         @Nullable HttpCredentialsInterface credentials,
                         @NonNull long contentLength) throws Exception;

    /**
     * Fetches user admin units from the server
     *
     * @param username       The username of the user
     * @param uri            where to fetch user details from
     * @param contentLength  contentLength requested by the server
     * @return ResponseMessageParser object that contains the response XML
     * @throws IOException can be thrown if files do not exist
     */
    @NonNull
    HttpGetResult fetchUnits(@NonNull String username,
                        @NonNull URI uri,
                        @Nullable HttpCredentialsInterface credentials,
                        @NonNull long contentLength) throws Exception;
}
