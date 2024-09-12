/*
 * Copyright (C) 2011 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.login;

import java.io.Serializable;

public class LoginDetails implements Serializable {

    private static final long serialVersionUID = 1L;

    private long user_id;
    private String user_name;
    private String phone;
    private String email;
    private String name;
    private String token;
    private String api_username;
    private String api_password;
    private String district;
    private String sub_county;
    private String parish;
    private String village;
    private String message;

    public LoginDetails(long user_id, String user_name, String phone, String email, String name, String token,
            String api_username, String api_password, String district, String sub_county, String parish,
            String village, String message) {
        this.user_id = user_id;
        this.user_name = user_name;
        this.phone = phone;
        this.email = email;
        this.name = name;
        this.token = token;
        this.api_username = api_username;
        this.api_password = api_password;
        this.district = district;
        this.sub_county = sub_county;
        this.parish = parish;
        this.village = village;
        this.message = message;
    }

    public long getUser_id() {
        return user_id;
    }

    public void setUser_id(long user_id) {
        this.user_id = user_id;
    }

    public String getUser_name() {
        return user_name;
    }

    public void setUser_name(String user_name) {
        this.user_name = user_name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getApi_username() {
        return api_username;
    }

    public void setApi_username(String api_username) {
        this.api_username = api_username;
    }

    public String getApi_password() {
        return api_password;
    }

    public void setApi_password(String api_password) {
        this.api_password = api_password;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getSub_county() {
        return sub_county;
    }

    public void setSub_county(String sub_county) {
        this.sub_county = sub_county;
    }

    public String getParish() {
        return parish;
    }

    public void setParish(String parish) {
        this.parish = parish;
    }

    public String getVillage() {
        return village;
    }

    public void setVillage(String village) {
        this.village = village;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
