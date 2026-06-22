package org.odk.collect.android.projects

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.odk.collect.analytics.Analytics
import org.odk.collect.android.R
import org.odk.collect.android.activities.AboutActivity
import org.odk.collect.android.activities.ActivityUtils
import org.odk.collect.android.activities.FirstLaunchActivity
import org.odk.collect.android.analytics.AnalyticsEvents
import org.odk.collect.android.databinding.ProjectSettingsDialogLayoutBinding
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.android.listeners.AdminUnitsTaskListener
import org.odk.collect.android.login.AdminUnitDetails
import org.odk.collect.android.login.LoginDetailsFetcher
import org.odk.collect.android.login.LoginSourceException
import org.odk.collect.android.login.LoginSourceExceptionMapper
import org.odk.collect.android.mainmenu.CurrentProjectViewModel
import org.odk.collect.android.mainmenu.MainMenuActivity
import org.odk.collect.android.preferences.screens.ProjectPreferencesActivity
import org.odk.collect.androidshared.ui.DialogFragmentUtils
import org.odk.collect.androidshared.ui.ToastUtils
import org.odk.collect.android.tasks.AdminUnitsTask
import org.odk.collect.projects.Project
import org.odk.collect.projects.ProjectsRepository
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import javax.inject.Inject

class ProjectSettingsDialog(private val viewModelFactory: ViewModelProvider.Factory) : DialogFragment(), AdminUnitsTaskListener {

    @Inject
    lateinit var projectsRepository: ProjectsRepository

    @Inject
    lateinit var projectDeleter: ProjectDeleter

    @Inject
    lateinit var settingsProvider: SettingsProvider

    @Inject
    lateinit var loginDetailsFetcher: LoginDetailsFetcher

    lateinit var binding: ProjectSettingsDialogLayoutBinding

    private lateinit var currentProjectViewModel: CurrentProjectViewModel

    private var adminUnitsTask: AdminUnitsTask? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        DaggerUtils.getComponent(context).inject(this)

        currentProjectViewModel = ViewModelProvider(
            requireActivity(),
            viewModelFactory
        )[CurrentProjectViewModel::class.java]
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = ProjectSettingsDialogLayoutBinding.inflate(LayoutInflater.from(context))

        currentProjectViewModel.currentProject.observe(this) { project ->
            binding.currentProject.setupView(project, settingsProvider.getUnprotectedSettings())
            binding.currentProject.contentDescription =
                getString(R.string.using_project, project.name)
            inflateListOfInActiveProjects(requireContext(), project)
        }

        binding.closeIcon.setOnClickListener {
            dismiss()
        }

        binding.logoutButton.setOnClickListener {
            deleteProject()
            dismiss()
        }

        binding.refreshAdminUnitsButton.setOnClickListener {
            refreshAdminUnits()
        }

        binding.generalSettingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), ProjectPreferencesActivity::class.java))
            dismiss()
        }

        binding.addProjectButton.setOnClickListener {
            DialogFragmentUtils.showIfNotShowing(
                QrCodeProjectCreatorDialog::class.java,
                requireActivity().supportFragmentManager
            )
            dismiss()
        }

        binding.aboutButton.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()
    }

    private fun refreshAdminUnits() {
        adminUnitsTask?.setDownloaderListener(null)
        adminUnitsTask?.cancel(true)

        val username = settingsProvider.getUnprotectedSettings().getString(ProjectKeys.KEY_METADATA_PHONENUMBER)
        if (username.isNullOrBlank()) {
            ToastUtils.showLongToast(requireContext(), getString(R.string.admin_units_refresh_failed))
            return
        }

        loginDetailsFetcher.updateAdminUnitsPath("/api/v1/adminunits")
        adminUnitsTask = AdminUnitsTask(loginDetailsFetcher)
        adminUnitsTask!!.setDownloaderListener(this)
        adminUnitsTask!!.execute(hashMapOf("username" to username))
    }

    override fun adminUnitsComplete(adminUnitDetails: AdminUnitDetails?, exception: LoginSourceException?) {
        adminUnitsTask?.setDownloaderListener(null)
        adminUnitsTask = null

        if (exception is LoginSourceException.UserNotAllowedAccess || adminUnitDetails?.message == ACCESS_REVOKED_MESSAGE) {
            MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.access_revoked_title)
                .setMessage(R.string.access_revoked_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    deleteProject()
                }
                .show()
        } else if (exception == null && adminUnitDetails != null && adminUnitDetails.message.isNullOrBlank()) {
            settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_DISTRICT, adminUnitDetails.district)
            settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_SUB_COUNTY, adminUnitDetails.sub_county)
            settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_PARISH, adminUnitDetails.parish)
            settingsProvider.getUnprotectedSettings().save(ProjectKeys.KEY_VILLAGE, adminUnitDetails.village)
            ToastUtils.showLongToast(requireContext(), getString(R.string.admin_units_refreshed))
        } else {
            val message = if (exception != null) {
                LoginSourceExceptionMapper(requireContext()).getMessage(exception)
            } else {
                adminUnitDetails?.message ?: getString(R.string.admin_units_refresh_failed)
            }

            MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.admin_units_refresh_failed)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun progressUpdate(currentFile: String?, progress: Int, total: Int) {
        // No progress UI for admin unit refreshes.
    }

    override fun adminUnitsCancelled() {
        adminUnitsTask?.setDownloaderListener(null)
        adminUnitsTask = null
    }

    override fun onDestroyView() {
        adminUnitsTask?.setDownloaderListener(null)
        adminUnitsTask?.cancel(true)
        adminUnitsTask = null
        super.onDestroyView()
    }

    fun deleteProject() {
        Analytics.log(AnalyticsEvents.DELETE_PROJECT)

        when (val deleteProjectResult = projectDeleter.deleteCurrentProject()) {
            is DeleteProjectResult.UnsentInstances -> {
                MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.cannot_logout_title)
                    .setMessage(R.string.cannot_delete_project_message_one)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            is DeleteProjectResult.RunningBackgroundJobs -> {
                MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.cannot_logout_title)
                    .setMessage(R.string.cannot_delete_project_message_two)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            is DeleteProjectResult.DeletedSuccessfully -> {
                val newCurrentProject = deleteProjectResult.newCurrentProject
                if (newCurrentProject != null) {
                    ActivityUtils.startActivityAndCloseAllOthers(
                        requireActivity(),
                        MainMenuActivity::class.java
                    )
                    ToastUtils.showLongToast(
                        requireContext(),
                        getString(
                            R.string.switched_project,
                            newCurrentProject.name
                        )
                    )
                } else {
                    ActivityUtils.startActivityAndCloseAllOthers(
                        requireActivity(),
                        FirstLaunchActivity::class.java
                    )
                }
            }
        }
    }

    private fun inflateListOfInActiveProjects(context: Context, currentProject: Project.Saved) {
        if (projectsRepository.getAll().none { it.uuid != currentProject.uuid }) {
            binding.topDivider.visibility = INVISIBLE
        } else {
            binding.topDivider.visibility = VISIBLE
        }

        projectsRepository.getAll().filter {
            it.uuid != currentProject.uuid
        }.forEach { project ->
            val projectView = ProjectListItemView(context)

            projectView.setOnClickListener {
                switchProject(project)
            }

            projectView.setupView(project, settingsProvider.getUnprotectedSettings(project.uuid))
            projectView.contentDescription = getString(R.string.switch_to_project, project.name)
            binding.projectList.addView(projectView)
        }
    }

    private fun switchProject(project: Project.Saved) {
        currentProjectViewModel.setCurrentProject(project)

        ActivityUtils.startActivityAndCloseAllOthers(requireActivity(), MainMenuActivity::class.java)
        ToastUtils.showLongToast(
            requireContext(),
            getString(R.string.switched_project, project.name)
        )
        dismiss()
    }

    companion object {
        private const val ACCESS_REVOKED_MESSAGE = "User not allowed access to system"
    }
}
