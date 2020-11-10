# Shared libraries for Jenkins

## Mulesoft pipeline

The contents of `vars/mule.groovy` contain a Mulesoft pipeline customized for use with the company Mulesoft Anypoint platform and Artifactory.

### Usage

To use the Mulesoft shared lib in a project pipeline, simply include it in the pipeline

```jenkinsfile
@Library('shared@master') _
mule("project-name", env.BUILD_TAG)
```

The `master` value can be replaced by any branch name from the `ent-jenkins-shared-libraries` repository. The `project name` should be replaced by the name of the project that's being built

### Build parameters

These parameters can be set in the `Build with parameters` form.

* `TARGET_ENVIRONMENT` (possible values `dev`, `sqa`, `prd` - default `dev`) is the name of the Anypoint environment that the application will be deployed to. The three available values should be reflected in the project's `pom.xml` file, in the `profiles` section. The value set here will be used in the `mvn -P<TARGET_ENVIRONMENT>` parameter while building and deploying the application.

* `BUSINESS_GROUP` (possible values `Business Apps`, `Enterprise Tech` - default `Business Apps`) is the name of the Anypoint business group that the application will be deployed to. The available values reflect the current Anypoint setup. The selected value is also available as an environment variable and can be used in the `pom.xml` files in the `properties.businessGroup` node.
```xml
<properties>
    <businessGroup>${env.BUSINESS_GROUP}`</businessGroup>
</properties>
```

* `SHOULD_DEPLOY` (possible values `true`, `false` - default `false`) determines whether the application should be deployed to the Anypoint platform. By default this switch is off, which means that the application will be built and published to Artifactory, but won't be published to Anypoint. Enabling this option will run and additional step after the build, publishing the artifact to Anypoint, using the business group and environment set in `BUSINESS_GROUP` AND `TARGET_ENVIRONMENT`

### Environment variables

The pipeline sets a couple of environment variables that can be used as parameters in the `pom.xml` file.

* `MULE_PROJECT` the name of the project passed to the pipeline
* `SHARED_DIR` a shared directory created at the beginning of the pipeline and available to every step
* `GIT_COMMIT` the hash of the Git commit
* `SEND_SLACK_NOTIFICATION` enable sending a Slack notification with the pipeline result
* `TARGET_ENVIRONMENT` the name of the Anypoint environment that the application will be deployed to
* `BUSINESS_GROUP` the name of the Anypoint business group that the application will be deployed to
* `RELEASE_NAME` the release name, consisting of the branch name and Git commit hash. For `release-*` branches, it will be prefixed with the release version
* `SHOULD_DEPLOY` flag indicating if the application should be deployed to Anypoint

### Pipeline steps

* `Clean` cleans the Maven working directory and downloads all the missing dependencies - also sets the built application version to the one prepared in the `RELEASE_NAME` parameter (additional information [here](https://maven.apache.org/plugins/maven-clean-plugin/usage.html]))
* `Run tests` runs MUnit tests (additional information [here](https://docs.mulesoft.com/munit/2.3/munit-maven-plugin))
* `Build and upload to Artifactory` creates a jar package and uploads it to Artifactory server defined in the `distributionManagement` node in the `pom.xml` file (additional information [here](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html))
* `Publish to Anypoint` first part of this step downloads the previously built artifact from Artifactory to the directory determined by the `OUTPUT_DIR` variable. The artifact must be declared in the `pom.xml` file, so the dependency plugin can pull it from the repository. The second part of this step deploys the fetched application to the Anypoint platform. The `BUSINESS_GROUP` AND `TARGET_ENVIRONMENT` determine where exactly the application will be deployed. The build will remain in progress until the application is finished deploying - the build result will be determined by the deployment result - build fails if the application won't start properly (more information on dependencies [here](https://maven.apache.org/plugins/maven-dependency-plugin/) and on the Mule deployment [here](https://docs.mulesoft.com/mule-runtime/4.3/deploy-to-cloudhub))

### Secrets

Some environment variables need to be set from Jenkins secrets for the pipeline to function properly.

| Environment variable | Jenkins secret name | Description |
| -------------------- | ------------------- | ----------- |
| `MULE_REPOSITORY_USERNAME` | `MULESOFT_NEXUS_REPOSITORY` | Credentials to Mulesoft Nexus Maven repository, necessary to download some of the Mule dependencies  |
| `MULE_REPOSITORY_PASSWORD` | | |
| `ARTIFACTORY_USERNAME` | `artifactory_svc_data_team` | Credentials to Artifactory |
| `ARTIFACTORY_PASSWORD` | | |
| `ANYPOINT_CLIENT_ID` |  `MULESOFT_ANYPOINT_CLIENT_<BUSINESS_GROUP_CODE>_<ENVIRONMENT>` | Mulesoft client id and secret - these values are business group and environment specific, so Jenkins secrets need to be created for each combination | 
| `ANYPOINT_CLIENT_SECRET` | | | 
| `ANYPOINT_USERNAME` | `MULESOFT_ANYPOINT_CREDENTIALS` | Anypoint user credentials - credentials for the Anypoint `svc_jenkins_automation` account should be used here |
| `ANYPOINT_PASSWORD` | | |
| `MULESOFT_KEY` | `MULESOFT_ANYPOINT_KEY_<BUSINESS_GROUP_CODE>_<ENVIRONMENT>` | Mulesoft secret key - also business group and environment specific, Jenkins secrets need to be created for each combination |
| `SPLUNK_TOKEN` | `MULESOFT_SPLUNK_TOKEN_<BUSINESS_GROUP_CODE>_<ENVIRONMENT_TYPE>` | Splunk key - specific to business group and the environment type.

Some explanation for the Jenkins secret name parameter names:
* `BUSINESS_GROUP_CODE` is a three-letter code of a business group (in this case the full names are abbreviated for the sake of variable names length) - possible values `BUS`, `ENT`
* `ENVIRONMENT` is the name of the target environment, it's basically the `TARGET_ENVIRONMENT` parameter, but upper-case - possible values `DEV`, `SQA`, `PRD`
* `ENVIRONMENT_TYPE` determines the production or non-production Splunk - possible values `PROD`, `NON-PROD`