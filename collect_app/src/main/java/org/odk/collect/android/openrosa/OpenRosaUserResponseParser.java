package org.odk.collect.android.openrosa;

import org.jetbrains.annotations.Nullable;
import org.kxml2.kdom.Document;
import org.odk.collect.android.login.AdminUnitDetails;
import org.odk.collect.android.login.LoginDetails;

import java.util.List;

public interface OpenRosaUserResponseParser {

    @Nullable LoginDetails parseLogin(Document document);

    @Nullable AdminUnitDetails parseAdminUnits(Document document);

}
