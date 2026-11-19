package org.odk.collect.android.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.odk.collect.android.configure.qr.AppConfigurationGenerator
import org.odk.collect.projects.Project
import org.odk.collect.projects.ProjectConfigurationResult
import org.odk.collect.projects.ProjectCreator
import org.odk.collect.projects.ProjectsRepository

class PreconfiguredProjectInitializerTest {
    private val projectsRepository = mock<ProjectsRepository>()
    private val projectCreator = mock<ProjectCreator>()
    private val configGenerator = mock<AppConfigurationGenerator>()

    private val initializer = PreconfiguredProjectInitializer(
        projectsRepository,
        projectCreator,
        configGenerator
    )

    @Test
    fun `blank URL leaves the regular ODK first launch untouched`() {
        assertFalse(initializer.configureIfNeeded(""))

        verifyNoInteractions(projectsRepository, projectCreator, configGenerator)
    }

    @Test
    fun `existing project is never changed or overwritten`() {
        val existing = Project.Saved("existing", Project.New("Existing", "E", "#123456"))
        whenever(projectsRepository.getAll()).thenReturn(listOf(existing))

        assertFalse(initializer.configureIfNeeded("https://collector.example/v1/projects/abc"))

        verifyNoInteractions(projectCreator, configGenerator)
    }

    @Test
    fun `a fresh installation provisions the URL without credentials`() {
        val url = "https://collector.example/v1/projects/abc"
        whenever(projectsRepository.getAll()).thenReturn(emptyList())
        whenever(configGenerator.getAppConfigurationAsJsonWithServerDetails(url, "", ""))
            .thenReturn("{\"general\":{}}")
        whenever(projectCreator.createNewProject("{\"general\":{}}", true))
            .thenReturn(ProjectConfigurationResult.SUCCESS)

        assertTrue(initializer.configureIfNeeded(url))

        verify(configGenerator).getAppConfigurationAsJsonWithServerDetails(url, "", "")
        verify(projectCreator).createNewProject("{\"general\":{}}", true)
    }

    @Test
    fun `failed project import is reported without claiming success`() {
        val url = "https://collector.example/v1/projects/abc"
        whenever(projectsRepository.getAll()).thenReturn(emptyList())
        whenever(configGenerator.getAppConfigurationAsJsonWithServerDetails(url, "", ""))
            .thenReturn("{}")
        whenever(projectCreator.createNewProject("{}", true))
            .thenReturn(ProjectConfigurationResult.INVALID_SETTINGS)

        assertFalse(initializer.configureIfNeeded(url))
    }
}
