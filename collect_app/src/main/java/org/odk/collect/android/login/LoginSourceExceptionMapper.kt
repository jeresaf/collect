package org.odk.collect.android.login

import android.content.Context
import org.odk.collect.android.R
import org.odk.collect.forms.FormSourceException
import org.odk.collect.strings.localization.getLocalizedString

class LoginSourceExceptionMapper(private val context: Context) {
    fun getMessage(exception: LoginSourceException?): String {
        return when (exception) {
            is LoginSourceException.AuthRequired -> {
                context.getLocalizedString(
                        R.string.auth_error
                ) + " " + context.getLocalizedString(
                        R.string.report_to_project_lead
                )
            }
            is LoginSourceException.Unreachable -> {
                context.getLocalizedString(
                    R.string.unreachable_error,
                    exception.serverUrl
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is LoginSourceException.SecurityError -> {
                context.getLocalizedString(
                    R.string.security_error,
                    exception.serverUrl
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is LoginSourceException.ServerError -> {
                context.getLocalizedString(
                    R.string.server_error,
                    exception.serverUrl,
                    exception.statusCode
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is LoginSourceException.ParseError -> {
                context.getLocalizedString(
                    R.string.invalid_response,
                    exception.serverUrl
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is LoginSourceException.UserNotAllowedAccess -> {
                context.getLocalizedString(R.string.access_revoked_message)
            }
            is LoginSourceException.ServerNotOpenRosaError -> {
                "This server does not correctly implement the OpenRosa formList API." + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            else -> {
                context.getLocalizedString(R.string.report_to_project_lead)
            }
        }
    }
}
