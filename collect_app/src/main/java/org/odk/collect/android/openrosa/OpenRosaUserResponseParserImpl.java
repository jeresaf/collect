package org.odk.collect.android.openrosa;

import org.javarosa.xform.parse.XFormParser;
import org.jetbrains.annotations.Nullable;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;
//import org.odk.collect.android.login.AdminUnitDetails;
import org.odk.collect.android.login.LoginDetails;

import timber.log.Timber;

public class OpenRosaUserResponseParserImpl implements OpenRosaUserResponseParser {

    private static final String NAMESPACE_PMT_COM_LOGIN = "https://mev.pdmis.go.ug/pmt/users";
    private static final String NAMESPACE_PMT_COM_ADMIN_UNITS = "https://mev.pdmis.go.ug/pmt/adminUnits";

    //Serious issue here with the response namespace
    private static final String NAMESPACE_OPENROSA_ORG_XFORMS_XFORMS_LIST = "http://openrosa.org/xforms/xformsList";
    private static final String NAMESPACE_OPENROSA_ORG_HTTP_RESPONSE = "http://openrosa.org/http/response";

    @Override
    @Nullable
    public LoginDetails parseLogin(Document document) {
        // Attempt OpenRosa 1.0 parsing
        Timber.e("Parsing User Start");
        Element xformsElement = document.getRootElement();
        Timber.e("Name: %s", xformsElement.getName());
        if (!xformsElement.getName().equals("users")) {
            if(xformsElement.getName().equals("OpenRosaResponse")) {
                if (!isResponseNamespacedElement(xformsElement)) {
                    Timber.e("Is not response name spaced");
                    return null;
                }
                int elements = xformsElement.getChildCount();
                String message;
                Timber.e("Elements: %d", elements);
                try {
                    for (int i = 0; i < elements; ++i) {
                        if (xformsElement.getType(i) != Element.ELEMENT) {
                            // e.g., whitespace (text)
                            continue;
                        }
                        Element xformElement = xformsElement.getElement(i);
                        if (!isResponseNamespacedElement(xformElement)) {
                            // someone else's extension?
                            continue;
                        }
                        String name = xformElement.getName();
                        if (!name.equalsIgnoreCase("message")) {
                            // someone else's extension?
                            continue;
                        }
                        message = XFormParser.getXMLText(xformElement, true);
                        if (message != null && message.length() == 0) {
                            Timber.e("Empty message");
                            return null;
                        }
                        return new LoginDetails(0, "", "", "", "", "",
                                "", "", "", "", "", "", message);
                    }
                } catch (Exception ignored) {}
            }
            return null;
        }
        Timber.e("NS: %b", isXformsListNamespacedElement(xformsElement) || isLoginNamespacedElement(xformsElement));
        if (!(isXformsListNamespacedElement(xformsElement) || isLoginNamespacedElement(xformsElement))) {
            return null;
        }

        LoginDetails loginDetails = null;
        int elements = xformsElement.getChildCount();
        Timber.e("Element count: %d", elements);
        try {
            for (int i = 0; i < elements; ++i) {
                if (xformsElement.getType(i) != Element.ELEMENT) {
                    // e.g., whitespace (text)
                    continue;
                }
                Element xformElement = xformsElement.getElement(i);
                if (!(isXformsListNamespacedElement(xformElement) || isLoginNamespacedElement(xformElement))) {
                    // someone else's extension?
                    continue;
                }
                String name = xformElement.getName();
                if (!name.equalsIgnoreCase("user")) {
                    // someone else's extension?
                    continue;
                }

                // this is something we know how to interpret
                long user_id = 0;
                String user_name = null;
                String phone = null;
                String email = null;
                String full_name = null;
                String token = null;
                String api_username = null;
                String api_password = null;
                String district = null;
                String sub_county = null;
                String parish = null;
                String village = null;
                // don't process descriptionUrl
                int fieldCount = xformElement.getChildCount();
                for (int j = 0; j < fieldCount; ++j) {
                    if (xformElement.getType(j) != Element.ELEMENT) {
                        // whitespace
                        continue;
                    }
                    Element child = xformElement.getElement(j);
                    if (!isXformsListNamespacedElement(child) || isLoginNamespacedElement(child)) {
                        // someone else's extension?
                        continue;
                    }
                    String tag = child.getName();
                    Timber.e("Tag: %s", tag);
                    switch (tag) {
                        case "userID":
                            user_id = Integer.parseInt(XFormParser.getXMLText(child, true));
                            if (user_id != 0) {
                                user_id = 0;
                            }
                            break;
                        case "userName":
                            user_name = XFormParser.getXMLText(child, true);
                            if (user_name != null && user_name.length() == 0) {
                                user_name = null;
                            }
                            break;
                        case "phone":
                            phone = XFormParser.getXMLText(child, true);
                            if (phone != null && phone.trim().isEmpty()) {
                                phone = null;
                            }
                            break;
                        case "email":
                            email = XFormParser.getXMLText(child, true);
                            if (email != null && email.length() == 0) {
                                email = null;
                            }
                            break;
                        case "name":
                            full_name = XFormParser.getXMLText(child, true);
                            if (full_name != null && full_name.length() == 0) {
                                full_name = null;
                            }
                            break;
                        case "token":
                            token = XFormParser.getXMLText(child, true);
                            if (token != null && token.length() == 0) {
                                token = null;
                            }
                            break;
                        case "api_username":
                            api_username = XFormParser.getXMLText(child, true);
                            if (api_username != null && api_username.length() == 0) {
                                api_username = null;
                            }
                            break;
                        case "api_password":
                            api_password = XFormParser.getXMLText(child, true);
                            if (api_password != null && api_password.length() == 0) {
                                api_password = null;
                            }
                            break;
                        case "district":
                            district = XFormParser.getXMLText(child, true);
                            if (district != null && district.length() == 0) {
                                district = null;
                            }
                            break;
                        case "sub_county":
                            sub_county = XFormParser.getXMLText(child, true);
                            if (sub_county != null && sub_county.length() == 0) {
                                sub_county = null;
                            }
                            break;
                        case "parish":
                            parish = XFormParser.getXMLText(child, true);
                            if (parish != null && parish.length() == 0) {
                                parish = null;
                            }
                            break;
                        case "village":
                            village = XFormParser.getXMLText(child, true);
                            if (village != null && village.length() == 0) {
                                village = null;
                            }
                            break;
                    }
                }
                Timber.e("Login details: user id - %s, username - %s, phone - %s, email - %s, full name - %s, token - %s, api username - %s, api password - %s, District - %s,Sub county - %s, Parish - %s, Village - %s", user_id, user_name, phone, email, full_name, token,
                        api_username, api_password, district, sub_county, parish, village);
                loginDetails = new LoginDetails(user_id, user_name, phone, email, full_name, token,
                        api_username, api_password, district, sub_county, parish, village, "");
            }
        } catch (Exception ignored) {}

        Timber.e("Parsing User End");

        return loginDetails;
    }

    /*
    @Override
    @Nullable
    public AdminUnitDetails parseAdminUnits(Document document) {
        // Attempt OpenRosa 1.0 parsing
        Timber.e("Parsing Admin Units Start");
        Element xformsElement = document.getRootElement();
        Timber.e("Name: %s", xformsElement.getName());
        if (!xformsElement.getName().equals("units")) {
            if(xformsElement.getName().equals("OpenRosaResponse")) {
                if (!isResponseNamespacedElement(xformsElement)) {
                    return null;
                }
                int elements = xformsElement.getChildCount();
                String message;
                if(elements > 0) {
                    if (xformsElement.getType(0) != Element.ELEMENT) {
                        // whitespace
                        return null;
                    }
                    Element child = xformsElement.getElement(0);
                    if (!isResponseNamespacedElement(child)) {
                        // someone else's extension?
                        return null;
                    }
                    String tag = child.getName();
                    if (tag.equals("message")) {
                        message = XFormParser.getXMLText(child, true);
                        if (message != null && message.length() == 0) {
                            return null;
                        }
                        return new AdminUnitDetails("", "", "", "", message);
                    }
                }
            }
            return null;
        }
        Timber.e("NS: %b", isAdminUnitsNamespacedElement(xformsElement));
        if (!isAdminUnitsNamespacedElement(xformsElement)) {
            return null;
        }

        AdminUnitDetails adminUnitDetails = null;
        int elements = xformsElement.getChildCount();
        Timber.e("Element count: %d", elements);
        try {
            String district = "";
            String sub_county = "";
            String parish = "";
            String village = "";
            for (int i = 0; i < elements; ++i) {
                if (xformsElement.getType(i) != Element.ELEMENT) {
                    // e.g., whitespace (text)
                    continue;
                }
                Element xformElement = xformsElement.getElement(i);
                if (!isAdminUnitsNamespacedElement(xformElement)) {
                    // someone else's extension?
                    continue;
                }
                String name = xformElement.getName();
                if (!name.equalsIgnoreCase("unit")) {
                    // someone else's extension?
                    if(name.equalsIgnoreCase("unit"))
                    continue;
                }

                // this is something we know how to interpret
                // don't process descriptionUrl
                int fieldCount = xformElement.getChildCount();
                for (int j = 0; j < fieldCount; ++j) {
                    if (xformElement.getType(j) != Element.ELEMENT) {
                        // whitespace
                        continue;
                    }
                    Element child = xformElement.getElement(j);
                    if (!isAdminUnitsNamespacedElement(child)) {
                        // someone else's extension?
                        continue;
                    }
                    String tag = child.getName();
                    switch (tag) {
                        case "district":
                            district = XFormParser.getXMLText(child, true);
                            if (district != null && district.length() == 0) {
                                district = "";
                            }
                            break;
                        case "sub_county":
                            sub_county = XFormParser.getXMLText(child, true);
                            if (sub_county != null && sub_county.length() == 0) {
                                sub_county = "";
                            }
                            break;
                        case "parish":
                            parish = XFormParser.getXMLText(child, true);
                            if (parish != null && parish.length() == 0) {
                                parish = "";
                            }
                            break;
                        case "village":
                            village = XFormParser.getXMLText(child, true);
                            if (village != null && village.length() == 0) {
                                village = "";
                            }
                            break;
                    }
                }

                adminUnitDetails = new AdminUnitDetails(district, sub_county, parish, village, "");
            }
        } catch (Exception ignored) {}

        Timber.e("Parsing Admin Units End");

        return adminUnitDetails;
    }

     */

    private static boolean isLoginNamespacedElement(Element e) {
        return e.getNamespace().equalsIgnoreCase(NAMESPACE_PMT_COM_LOGIN);
    }

    /*
    private static boolean isAdminUnitsNamespacedElement(Element e) {
        return e.getNamespace().equalsIgnoreCase(NAMESPACE_DERON_COM_ADMIN_UNITS);
    }

     */

    private static boolean isXformsListNamespacedElement(Element e) {
        return e.getNamespace().equalsIgnoreCase(NAMESPACE_OPENROSA_ORG_XFORMS_XFORMS_LIST);
    }

    private static boolean isResponseNamespacedElement(Element e) {
        return e.getNamespace().equalsIgnoreCase(NAMESPACE_OPENROSA_ORG_HTTP_RESPONSE);
    }
}
