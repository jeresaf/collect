package org.odk.collect.android.projects

import org.odk.collect.android.configure.qr.AppConfigurationGenerator
import org.odk.collect.projects.ProjectConfigurationResult
import org.odk.collect.projects.ProjectCreator
import org.odk.collect.projects.ProjectsRepository

/**
 * Creates a single server project on a fresh installation if an HTTPS URL was
 * supplied to the build. Existing projects and their data are never modified.
 *
 * Authentication credentials are deliberately not provisioned by the APK.
 */
class PreconfiguredProjectInitializer(
    private val projectsRepository: ProjectsRepository,
    private val projectCreator: ProjectCreator,
    private val configGenerator: AppConfigurationGenerator
) {
    fun configureIfNeeded(serverUrl: String): Boolean {
        if (serverUrl.isBlank() || projectsRepository.getAll().isNotEmpty()) {
            return false
        }

        val configuration = configGenerator.getAppConfigurationAsJsonWithServerDetails(
            serverUrl,
            "",
            ""
        )
        return projectCreator.createNewProject(configuration, true) == ProjectConfigurationResult.SUCCESS
    }
}
