package org.odk.collect.android.activities

import android.app.ProgressDialog
import android.content.DialogInterface
import android.os.AsyncTask
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import org.javarosa.core.util.Map
import org.odk.collect.android.R
import org.odk.collect.android.databinding.FirstLaunchLayoutBinding
import org.odk.collect.android.fragments.dialogs.LoginDialogFragment
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.android.listeners.LoginTaskListener
import org.odk.collect.android.login.LoginDetails
import org.odk.collect.android.login.LoginDetailsFetcher
import org.odk.collect.android.login.LoginSourceException
import org.odk.collect.android.login.LoginSourceExceptionMapper
import org.odk.collect.android.mainmenu.MainMenuActivity
import org.odk.collect.android.projects.CurrentProjectProvider
import org.odk.collect.android.tasks.LoginTask
import org.odk.collect.android.utilities.DialogUtils
import org.odk.collect.android.version.VersionInformation
import org.odk.collect.androidshared.network.NetworkStateProvider
import org.odk.collect.androidshared.ui.DialogFragmentUtils
import org.odk.collect.androidshared.ui.ToastUtils
import org.odk.collect.projects.Project
import org.odk.collect.projects.ProjectsRepository
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import timber.log.Timber
import javax.inject.Inject

class FirstLaunchActivity : LocalizedActivity(), LoginTaskListener, LoginDialogFragment.LoginDialogFragmentListener {

    @Inject
    lateinit var projectsRepository: ProjectsRepository

    @Inject
    lateinit var versionInformation: VersionInformation

    @Inject
    lateinit var currentProjectProvider: CurrentProjectProvider

    @Inject
    lateinit var settingsProvider: SettingsProvider

    @Inject
    lateinit var loginDetailsFetcher: LoginDetailsFetcher

    @Inject
    lateinit var connectivityProvider: NetworkStateProvider

    private lateinit var binding: FirstLaunchLayoutBinding

    lateinit var alertDialog: AlertDialog

    private var loginTask: LoginTask? = null

    private var cancelDialog: ProgressDialog? = null

    private val EXIT = true
    private val DO_NOT_EXIT = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DaggerUtils.getComponent(this).inject(this)

        binding = FirstLaunchLayoutBinding.inflate(layoutInflater)
        binding.apply {
            setContentView(this.root)

            loginButton.setOnClickListener {
                //Analytics.log(AnalyticsEvents.TRY_DEMO)
                login()
            }

            /*
            configureViaQrButton.setOnClickListener {
                DialogFragmentUtils.showIfNotShowing(
                    QrCodeProjectCreatorDialog::class.java,
                    supportFragmentManager
                )
            }

            configureManuallyButton.setOnClickListener {
                DialogFragmentUtils.showIfNotShowing(
                    ManualProjectCreatorDialog::class.java,
                    supportFragmentManager
                )
            }

             */

            appName.text = String.format(
                "%s %s",
                getString(R.string.collect_app_name),
                versionInformation.versionToDisplay
            )

            /*
            configureLater.addOnClickListener {
                Analytics.log(AnalyticsEvents.TRY_DEMO)

                projectsRepository.save(Project.DEMO_PROJECT)
                currentProjectProvider.setCurrentProject(Project.DEMO_PROJECT_ID)

                ActivityUtils.startActivityAndCloseAllOthers(
                    this@FirstLaunchActivity,
                    MainMenuActivity::class.java
                )
            }

             */
        }
    }

    /**
     * Starts the download task and shows the progress dialog.
     */
    private fun login() {
        if (!connectivityProvider.isDeviceOnline) {
            ToastUtils.showShortToast(this, R.string.no_connection)
        } else {
            DialogFragmentUtils.showIfNotShowing(LoginDialogFragment::class.java, supportFragmentManager)
            if (loginTask != null
                    && loginTask!!.status != AsyncTask.Status.FINISHED) {
                return  // we are already doing the download!!!
            } else if (loginTask != null) {
                loginTask!!.setDownloaderListener(null)
                loginTask!!.cancel(true)
                loginTask = null
            }
            loginDetailsFetcher.updateLoginPath("/api/v1/login")
            loginTask = LoginTask(loginDetailsFetcher)
            loginTask!!.setDownloaderListener(this)

            if(binding.username.text.toString().isEmpty() || binding.password.text.toString().isEmpty()) {
                binding.username.error = "Field is required"
                binding.password.error = "Field is required"
            } else {
                binding.username.error = null
                binding.password.error = null
                val loginDetailsStr = Map<String, String>()
                loginDetailsStr.put("username", binding.username.text.toString())
                loginDetailsStr.put("password", binding.password.text.toString())

                loginTask!!.execute(loginDetailsStr)
            }

        }
    }

    override fun loginCancelled() {
        if (loginTask != null) {
            loginTask!!.setDownloaderListener(null)
            loginTask = null
        }

        if (cancelDialog != null && cancelDialog!!.isShowing) {
            cancelDialog!!.dismiss()
        }
    }

    override fun loginComplete(loginDetails: LoginDetails?, exception: LoginSourceException?) {
        if (loginTask != null) {
            loginTask!!.setDownloaderListener(null)
        }

        DialogFragmentUtils.dismissDialog(LoginDialogFragment::class.java, supportFragmentManager)

        if(exception == null) {
            if (loginDetails != null) {
                if(loginDetails.message == null || loginDetails.message.isEmpty()) {
                    projectsRepository.save(Project.PROJECT)
                    currentProjectProvider.setCurrentProject(Project.PROJECT_ID)

                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_METADATA_USERNAME, loginDetails.name)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_METADATA_PHONENUMBER, loginDetails.phone)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_METADATA_EMAIL, loginDetails.email)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_USERNAME, loginDetails.api_username)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_PASSWORD, loginDetails.api_password)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_DISTRICT, loginDetails.district)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_SUB_COUNTY, loginDetails.sub_county)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_PARISH, loginDetails.parish)
                    settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_VILLAGE, loginDetails.village)

                    ActivityUtils.startActivityAndCloseAllOthers(this, MainMenuActivity::class.java)
                } else {
                    val dialogTitle = getString(R.string.login_error)
                    createAlertDialog(dialogTitle, loginDetails.message, DO_NOT_EXIT)
                }
            }
        } else {
            val dialogMessage = LoginSourceExceptionMapper(this).getMessage(exception)
            val dialogTitle = getString(R.string.login_error)
            createAlertDialog(dialogTitle, dialogMessage, DO_NOT_EXIT)
            Timber.e("%s", exception.message)
        }
    }

    override fun progressUpdate(currentFile: String?, progress: Int, total: Int) {
        val fragment = supportFragmentManager.findFragmentByTag(LoginDialogFragment::class.java.name) as LoginDialogFragment?

        fragment?.setMessage(getString(R.string.logging_in))
    }

    override fun onCancelLogin() {
        if (loginTask != null) {
            loginTask!!.setDownloaderListener(null)
            loginTask!!.cancel(true)
            loginTask = null
        }

        if (loginTask != null) {
            createCancelDialog()
            loginTask!!.cancel(true)
        }
    }

    /**
     * Creates an alert dialog with the given title and message. If shouldExit is set to true, the
     * activity will exit when the user clicks "ok".
     */
    private fun createAlertDialog(title: String, message: String, shouldExit: Boolean) {
        alertDialog = AlertDialog.Builder(this).create()
        alertDialog.setTitle(title)
        alertDialog.setMessage(message)
        val quitListener = DialogInterface.OnClickListener { dialog, i ->
            when (i) {
                DialogInterface.BUTTON_POSITIVE -> {
                    // successful download, so quit
                    // Also quit if in download_mode only(called by another app/activity just to download)
                    if (shouldExit) {
                        finish()
                    }
                }
            }
        }
        alertDialog.setCancelable(false)
        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.ok), quitListener)
        alertDialog.setIcon(android.R.drawable.ic_dialog_info)
        DialogUtils.showDialog(alertDialog, this)
    }

    private fun createCancelDialog() {
        cancelDialog = ProgressDialog(this)
        cancelDialog!!.setTitle(getString(R.string.canceling))
        cancelDialog!!.setMessage(getString(R.string.please_wait))
        cancelDialog!!.setIcon(android.R.drawable.ic_dialog_info)
        cancelDialog!!.setIndeterminate(true)
        cancelDialog!!.setCancelable(false)
        DialogUtils.showDialog(cancelDialog, this)
    }
}
