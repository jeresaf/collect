package org.odk.collect.android.login;

public class AdminUnitDetails {
    private final String district;
    private final String sub_county;
    private final String village;
    private final String parish;
    private final String message;

    public AdminUnitDetails(String district, String sub_county, String village, String parish, String message) {
        this.district = district;
        this.sub_county = sub_county;
        this.village = village;
        this.parish = parish;
        this.message = message;
    }

    public String getDistrict() {
        return district;
    }

    public String getSub_county() {
        return sub_county;
    }

    public String getVillage() {
        return village;
    }

    public String getParish() {
        return parish;
    }

    public String getMessage() {
        return message;
    }
}
