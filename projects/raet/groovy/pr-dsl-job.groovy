// $project Jenkins jobs seed script
import groovy.json.*
import groovy.text.*
import jenkins.model.Jenkins


// get current thread / Executor
def thr = Thread.currentThread()
// get current build
def build = thr?.executable

// Common variable Definitions
def project = new JsonSlurper().parseText(build.getEnvironment().SEED_DATA)
def pull_request_admins = new JsonSlurper().parseText(build.getEnvironment().PULL_REQUEST_ADMINS)

// Job rotation defaults
def default_days_to_keep = 90
def default_nr_of_jobs_to_keep = 180
def default_artifact_days_to_keep = default_days_to_keep
def default_artifact_nr_of_jobs_to_keep = default_nr_of_jobs_to_keep

// Job Timeout defauls
def default_timeout_percent = 150
def default_timeout_builds = 10
def default_timeout_minutes = 15

def new_prs = [:]
def template_engine = new SimpleTemplateEngine()

// Define the folder structure
folder(project.name) {
    displayName(project.display_name)
    description(project.description)
}
folder("${project.name}/pr") {
    displayName('Pull Requests')
    description(project.description)
}
project.pull_requests.each() { pr ->
    folder("${project.name}/pr/${pr.number}") {
        displayName("PR #${pr.number}")
        description(pr.rendered_description)
    }

    try {
        existing_job = Jenkins.instance.getJob(project.name).getJob('pr').getJob("${pr.number}").getJob('main-build')
        if ( existing_job == null ) {
            new_prs[pr.number] = pr.sha
        }
    } catch(e) {
        // no existing job
        new_prs[pr.number] = pr.sha
    }

    // PR Main Job
    buildFlowJob("${project.name}/pr/${pr.number}/main-build") {
        displayName("Main Build")
        description(pr.rendered_description)
        label('worker')
        concurrentBuild(allowConcurrentBuild = false)

        configure {
            it.appendNode('buildNeedsWorkspace').setValue(true)
            job_properties = it.get('properties').get(0)
            github_project_property = job_properties.appendNode(
                'com.coravy.hudson.plugins.github.GithubProjectProperty')
            github_project_property.appendNode('projectUrl').setValue("https://github.com/${project.repo}")
            slack_notifications = job_properties.appendNode(
                'jenkins.plugins.slack.SlackNotifier_-SlackJobProperty')
            slack_notifications.appendNode('room').setValue('#jenkins')
            slack_notifications.appendNode('startNotification').setValue(false)
            slack_notifications.appendNode('notifySuccess').setValue(true)
            slack_notifications.appendNode('notifyAborted').setValue(true)
            slack_notifications.appendNode('notifyNotBuilt').setValue(true)
            slack_notifications.appendNode('notifyFailure').setValue(true)
            slack_notifications.appendNode('notifyBackToNormal').setValue(true)
            job_publishers = it.get('publishers').get(0)
            job_publishers.appendNode(
                'org.zeroturnaround.jenkins.flowbuildtestaggregator.FlowTestAggregator',
                [plugin: 'build-flow-test-aggregator@latest']
            )
            job_publishers.appendNode(
                'jenkins.plugins.slack.SlackNotifier',
                [plugin: slack]
            )
        }

        wrappers {
            // Cleanup the workspace before starting
            preBuildCleanup()

            // Add timestamps to console log
            timestamps()

            // Color Support to console log
            colorizeOutput('xterm')

            // Build Timeout
            timeout {
                elastic(
                    percentage = default_timeout_percent,
                    numberOfBuilds = default_timeout_builds,
                    minutesDefault= default_timeout_minutes
                )
                writeDescription('Build failed due to timeout after {0} minutes')
            }
        }

        // Delete old jobs
        logRotator(
            default_days_to_keep,
            default_nr_of_jobs_to_keep,
            default_artifact_days_to_keep,
            default_artifact_nr_of_jobs_to_keep
        )

        // Job Triggers
        triggers {
            pullRequest {
                useGitHubHooks()
                permitAll()
                triggerPhrase('Go Go Jenkins!')
                admins(pull_request_admins.usernames)
            }
        }

        template_context = [
            project: 'raet',
            pr_number: pr.number
        ]
        script_template = template_engine.createTemplate(
            readFileFromWorkspace('maintenance/jenkins-seed', 'common/templates/pr-main-build-flow.groovy')
        )
        rendered_script_template = script_template.make(template_context.withDefault{ null })
        buildFlow(
            rendered_script_template.toString()
        )

        publishers {
            extendedEmail('$DEFAULT_RECIPIENTS', '$DEFAULT_SUBJECT', '$DEFAULT_CONTENT') {
                trigger(
                    triggerName: 'Failure',
                    subject: '$DEFAULT_SUBJECT',
                    body: '$DEFAULT_CONTENT',
                    recipientList: '$DEFAULT_RECIPIENTS',
                    sendToRecipientList: true
                )
                configure { node ->
                    node.appendNode('presendScript').setValue(
                        readFileFromWorkspace('maintenance/jenkins-seed', 'common/groovy/inject-submitter-email-address.groovy')
                    )
                }
            }

            groovyPostBuild(
                readFileFromWorkspace('maintenance/jenkins-seed', 'common/groovy/post-build-set-commit-status.groovy')
            )
        }
    }

    // PR Clone Job
    freeStyleJob("${project.name}/pr/${pr.number}/clone") {
        displayName("Clone Repository")

        concurrentBuild(allowConcurrentBuild = true)
        description(pr.rendered_description)
        label('worker')

        parameters {
            stringParam('GIT_COMMIT')
        }

        configure {
            job_properties = it.get('properties').get(0)
            job_properties.appendNode(
                'hudson.plugins.copyartifact.CopyArtifactPermissionProperty').appendNode(
                    'projectNameList').appendNode(
                        'string').setValue("${project.name}/pr/${pr.number}/*")
            github_project_property = job_properties.appendNode(
                'com.coravy.hudson.plugins.github.GithubProjectProperty')
            github_project_property.appendNode('projectUrl').setValue("https://github.com/${project.repo}")
        }

        wrappers {
            // Cleanup the workspace before starting
            preBuildCleanup()

            // Add timestamps to console log
            timestamps()

            // Color Support to console log
            colorizeOutput('xterm')

            // Build Timeout
            timeout {
                elastic(
                    percentage = default_timeout_percent,
                    numberOfBuilds = default_timeout_builds,
                    minutesDefault= default_timeout_minutes
                )
                writeDescription('Build failed due to timeout after {0} minutes')
            }
        }

        // Delete old jobs
        /* Since we're just cloning the repository in order to make it an artifact to
         * user as workspace for all other jobs, we only need to keep the artifact for
         * a couple of minutes. Since one day is the minimum....
         */
        logRotator(
            default_days_to_keep,
            default_nr_of_jobs_to_keep,
            1,  //default_artifact_days_to_keep,
            default_artifact_nr_of_jobs_to_keep
        )

        // scm configuration
        scm {
            git {
                remote {
                    github(project.repo, protocol='https')
                    refspec('+refs/pull/*:refs/remotes/origin/pr/*')
                }
                branch("origin/pr/${pr.number}/merge")
                configure {
                    git_extensions = it.appendNode('extensions')
                    extension = git_extensions.appendNode('hudson.plugins.git.extensions.impl.ChangelogToBranch')
                    options = extension.appendNode('options')
                    options.appendNode('compareRemote').setValue('origin')
                    options.appendNode('compareTarget').setValue("pr/${pr.number}/head")
                }
            }
        }
        checkoutRetryCount(3)

        template_context = [
            commit_status_context: 'ci/clone',
            github_repo: project.repo,
            virtualenv_name: 'raet-pr',
            virtualenv_setup_state_name: 'projects.clone'
        ]
        script_template = template_engine.createTemplate(
            readFileFromWorkspace('maintenance/jenkins-seed', 'common/templates/branches-envvars-commit-status.groovy')
        )
        rendered_script_template = script_template.make(template_context.withDefault{ null })

        environmentVariables {
            groovy(rendered_script_template.toString())
        }

        // Job Steps
        steps {
            // Setup the required virtualenv
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'common/scripts/prepare-virtualenv.sh'))

            // Compress the checked out workspace
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'common/scripts/compress-workspace.sh'))
        }

        publishers {
            archiveArtifacts {
                pattern('workspace.cpio.xz')
                pattern('*.log')
            }

            script_template = template_engine.createTemplate(
                readFileFromWorkspace('maintenance/jenkins-seed', 'common/groovy/post-build-set-commit-status.groovy')
            )
            rendered_script_template = script_template.make(template_context.withDefault{ null })

            groovyPostBuild(rendered_script_template.toString())
        }
    }

    // PR Lint Job
    freeStyleJob("${project.name}/pr/${pr.number}/lint") {
        displayName("Lint")
        concurrentBuild(allowConcurrentBuild = true)
        description(pr.rendered_description)
        label('worker')

        // Parameters Definition
        parameters {
            stringParam('GIT_COMMIT')
            stringParam('CLONE_BUILD_ID')
        }

        configure {
            job_properties = it.get('properties').get(0)
            github_project_property = job_properties.appendNode(
                'com.coravy.hudson.plugins.github.GithubProjectProperty')
            github_project_property.appendNode('projectUrl').setValue("https://github.com/${project.repo}")
        }

        wrappers {
            // Cleanup the workspace before starting
            preBuildCleanup()

            // Cleanup the workspace before starting
            preBuildCleanup()

            // Add timestamps to console log
            timestamps()

            // Color Support to console log
            colorizeOutput('xterm')

            // Build Timeout
            timeout {
                elastic(
                    percentage = default_timeout_percent,
                    numberOfBuilds = default_timeout_builds,
                    minutesDefault= default_timeout_minutes
                )
                writeDescription('Build failed due to timeout after {0} minutes')
            }
        }

        // Delete old jobs
        logRotator(
            default_days_to_keep,
            default_nr_of_jobs_to_keep,
            default_artifact_days_to_keep,
            default_artifact_nr_of_jobs_to_keep
        )

        template_context = [
            commit_status_context: 'ci/lint',
            github_repo: project.repo,
            virtualenv_name: 'raet-pr',
            virtualenv_setup_state_name: 'projects.raet.lint'
        ]
        script_template = template_engine.createTemplate(
            readFileFromWorkspace('maintenance/jenkins-seed', 'common/templates/branches-envvars-commit-status.groovy')
        )
        rendered_script_template = script_template.make(template_context.withDefault{ null })

        environmentVariables {
            groovy(rendered_script_template.toString())
        }

        // Job Steps
        steps {
            // Setup the required virtualenv
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'common/scripts/prepare-virtualenv.sh'))

            // Copy the workspace artifact
            copyArtifacts("${project.name}/pr/${pr.number}/clone", 'workspace.cpio.xz') {
                buildNumber('${CLONE_BUILD_ID}')
            }
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'common/scripts/decompress-workspace.sh'))

            // Run Lint Code
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'projects/raet/scripts/run-lint.sh'))
        }

        publishers {
            // Report Violations
            violations {
                pylint(10, 999, 999, 'pylint-report*.xml')
            }

            script_template = template_engine.createTemplate(
                readFileFromWorkspace('maintenance/jenkins-seed', 'common/groovy/post-build-set-commit-status.groovy')
            )
            rendered_script_template = script_template.make(template_context.withDefault{ null })

            groovyPostBuild(rendered_script_template.toString())

            archiveArtifacts {
                pattern('*.log')
                allowEmpty(true)
            }
        }
    }

    // PR Unit Tests
    freeStyleJob("${project.name}/pr/${pr.number}/tests") {
        displayName("Tests")
        concurrentBuild(allowConcurrentBuild = true)
        description(pr.rendered_description)
        label('worker')

        // Parameters Definition
        parameters {
            stringParam('GIT_COMMIT')
            stringParam('CLONE_BUILD_ID')
        }

        configure {
            job_properties = it.get('properties').get(0)
            github_project_property = job_properties.appendNode(
                'com.coravy.hudson.plugins.github.GithubProjectProperty')
            github_project_property.appendNode('projectUrl').setValue("https://github.com/${project.repo}")
            job_publishers = it.get('publishers').get(0)
            performance_plugin = job_publishers.appendNode(
                'hudson.plugins.performance.PerformancePublisher',
                [plugin: "performance@latest"]
            )
            performance_plugin.appendNode('errorFailedThreshold').setValue(0)
            performance_plugin.appendNode('errorUnstableThreshold').setValue(0)
            performance_plugin.appendNode('errorUnstableResponseTimeThreshold')
            performance_plugin.appendNode('relativeFailedThresholdPositive').setValue(0.0)
            performance_plugin.appendNode('relativeFailedThresholdNegative').setValue(0.0)
            performance_plugin.appendNode('relativeUnstableThresholdPositive').setValue(0.0)
            performance_plugin.appendNode('relativeUnstableThresholdNegative').setValue(0.0)
            performance_plugin.appendNode('nthBuildNumber').setValue(0)
            performance_plugin.appendNode('modeRelativeThresholds').setValue(false)
            performance_plugin.appendNode('configType').setValue('ART')
            performance_plugin.appendNode('modeOfThreshold').setValue(false)
            performance_plugin.appendNode('compareBuildPrevious').setValue(true)
            performance_plugin.appendNode('xml')
            performance_plugin_parsers = performance_plugin.appendNode('parsers')
            junit_parser = performance_plugin_parsers.appendNode('hudson.plugins.performance.JUnitParser')
            junit_parser.appendNode('glob').setValue('nosetests.xml')
            performance_plugin.appendNode('modeThroughput').setValue(true)
        }

        wrappers {
            // Cleanup the workspace before starting
            preBuildCleanup()

            // Add timestamps to console log
            timestamps()

            // Color Support to console log
            colorizeOutput('xterm')

            // Build Timeout
            timeout {
                elastic(
                    percentage = default_timeout_percent,
                    numberOfBuilds = default_timeout_builds,
                    minutesDefault= default_timeout_minutes
                )
                writeDescription('Build failed due to timeout after {0} minutes')
            }

        }

        template_context = [
            commit_status_context: 'ci/unit',
            github_repo: project.repo,
            virtualenv_name: 'raet-pr',
            virtualenv_setup_state_name: 'projects.raet.unit'
        ]
        script_template = template_engine.createTemplate(
            readFileFromWorkspace('maintenance/jenkins-seed', 'common/templates/branches-envvars-commit-status.groovy')
        )
        rendered_script_template = script_template.make(template_context.withDefault{ null })

        environmentVariables {
            groovy(rendered_script_template.toString())
        }

        // Job Steps
        steps {
            // Setup the required virtualenv
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'common/scripts/prepare-virtualenv.sh'))

            // Copy the workspace artifact
            copyArtifacts("${project.name}/pr/${pr.number}/clone", 'workspace.cpio.xz') {
                buildNumber('${CLONE_BUILD_ID}')
            }
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'common/scripts/decompress-workspace.sh'))

            // Run Unit Tests
            shell(readFileFromWorkspace('maintenance/jenkins-seed', 'projects/raet/scripts/run-unit.sh'))
        }

        publishers {
            // Report Coverage
            cobertura('coverage.xml') {
                failNoReports = false
            }

            // Junit Reports
            archiveJunit('nosetests.xml') {
                retainLongStdout(true)
                testDataPublishers {
                    publishTestStabilityData()
                }
            }

            script_template = template_engine.createTemplate(
                readFileFromWorkspace('maintenance/jenkins-seed', 'common/groovy/post-build-set-commit-status.groovy')
            )
            rendered_script_template = script_template.make(template_context.withDefault{ null })

            groovyPostBuild(rendered_script_template.toString())

            archiveArtifacts {
                pattern('*.log')
                allowEmpty(true)
            }
        }
    }
}

// Write any new PR's to a file so we can trigger then in the post build step
new_prs_file = build.getWorkspace().child('new-prs.txt')
new_prs_file.deleteContents()
json_data = new JsonBuilder(new_prs)
new_prs_file.write(json_data.toString(), 'UTF-8')
