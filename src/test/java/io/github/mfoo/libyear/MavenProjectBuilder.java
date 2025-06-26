/*
 * Copyright 2023 Martin Foot
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.mfoo.libyear;

import java.util.Collections;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;

class MavenProjectBuilder {

    private List<Dependency> dependencyList = Collections.emptyList();

    private List<Plugin> pluginList = Collections.emptyList();

    private List<Dependency> dependencyManagementDependencyList = Collections.emptyList();

    private List<Plugin> pluginManagementPluginList = Collections.emptyList();

    public MavenProjectBuilder withDependencies(List<Dependency> dependencyList) {
        this.dependencyList = dependencyList;
        return this;
    }

    public MavenProjectBuilder withPlugins(List<Plugin> pluginList) {
        this.pluginList = pluginList;
        return this;
    }

    public MavenProjectBuilder withDependencyManagementDependencyList(List<Dependency> dependencyList) {
        this.dependencyManagementDependencyList = dependencyList;
        return this;
    }

    public MavenProjectBuilder withPluginManagementPluginList(List<Plugin> pluginList) {
        this.pluginManagementPluginList = pluginList;
        return this;
    }

    public MavenProject build() {
        Model model = new Model() {
            {
                setGroupId("default-group");
                setArtifactId("default-artifact");
                setVersion("1.0.0-SNAPSHOT");

                setDependencies(dependencyList);

                Build build = new Build();
                setBuild(build);

                build.setPlugins(pluginList);

                PluginManagement pluginManagement = new PluginManagement();
                build.setPluginManagement(pluginManagement);
                pluginManagement.setPlugins(pluginManagementPluginList);

                DependencyManagement dependencyManagement = new DependencyManagement();
                setDependencyManagement(dependencyManagement);
                dependencyManagement.setDependencies(dependencyManagementDependencyList);
            }
        };

        MavenProject project = new MavenProject();
        project.setModel(model);
        project.setOriginalModel(model);

        return project;
    }
}
