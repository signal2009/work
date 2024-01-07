def call(Map config = [:]) {
    String appName = config.appName
    String teamName = config.teamName
    String deployXmlPath = config.deployXmlPath ?: 'deployit-manifest.xml'
    Boolean onlyUploadToNexus = config.onlyUploadToNexus ?: false
    String xldPackageIdSuffix = config.xldPackageIdSuffix

    String xldEnvironmentId
    String buildNumb
    String ghOwner

    Boolean RUN_XLD_DEPLOY
    Boolean UPLOAD_ARTIFACTS_NEXUS

    String BUILD_TAG

    // Get all the checkmarx variables
    String checkmarxProjectName = config.checkmarxProjectName
    String checkmarxTeamPath = config.checkmarxTeamPath                 // Example: DisputesValueStream\\MerchantDisputes

    // Setting up variables for Blackduck
    String blackduckProjectName = config.blackduckProjectName
    String blackduckProjectVersion = config.blackduckProjectVersion
    String blackduckBuc  =  config.blackduckBuc                         // Probably not needed
    String blackduckFiles  =  config.blackduckFiles                     // Consider using - 'src'
    String blackduckUrl = 'https://fis2.app.blackduck.com'
    String blackduckCredId = config.blackduckCredId
    String scanMode = 'RAPID'                                           // For all non default branches run RAPID Blackduck Scan

    String nexusArtifactUrl = ''

    pipeline {
        agent {
            kubernetes {
                label 'launchpad-ui'
                defaultContainer 'ui'
                yaml libraryResource('agents/k8s/ui-nvm.yaml')
            }
        }

        parameters {
            choice(name: 'DEPLOYMENT_ENVIRONMENT',
                    choices: "dev\n" +
                            "test\n" +
                            "load\n" +
                            "stage\n",
                    description: 'Which environment to deploy to?')
            booleanParam(name: 'RUN_SONARQUBE_SCAN', defaultValue: true, description: 'Scan code w/ SonarQube')
            booleanParam(name: 'RUN_CHECKMARX_SCAN', defaultValue: true, description: 'Scan code w/ Checkmarx')
            booleanParam(name: 'RUN_BLACKDUCK_SCAN', defaultValue: true, description: 'Scan code w/ Black Duck')
            booleanParam(name: 'RUN_NPM_AUDIT', defaultValue: true, description: 'Run NPM Audit.')
            booleanParam(name: 'RUN_DEPLOY', defaultValue: false, description: 'Deploy artifacts to XLD')
            booleanParam(name: 'UPLOAD_NEXUS', defaultValue: false, description: 'upload to nexus')
            
            
            booleanParam(name: 'RELEASE_BUILD', defaultValue: false, description: 'Whether to add the package.json version label to the tag')
        }

        environment {
            APP_NAME = "${appName}"
            TEAM_NAME = "${teamName}"
            // cannot invoke readJSON in the environment block only sh
            BUILD_VERSION =  sh( script: """ node -p "require('./package.json').version"  """, returnStdout: true).trim()
            K8S_NAMESPACE = "${k8sNamespace}"
            XLD_APPLICATION_FOLDER = "${xldPackageIdSuffix}"
            RESOURCE_YAML_LOCATION = "${resourcesYamlPath}"
            //IMAGE_TAG = buildImageTag(imageTag, env.BRANCH_NAME, env.BUILD_VERSION, env.BUILD_NUMBER, params.RELEASE_BUILD)
            XLD_APP_VERSION = buildXldVersion(env.BUILD_VERSION, env.BUILD_NUMBER, env.BRANCH_NAME, params.RELEASE_BUILD)
            //TODO: if engine is not present fail build
            NODE_VERSION =  sh( script: """ node -p "require('./package.json').engines.node"  """, returnStdout: true).trim()
            //TODO: Once the mirror repository is created in nexus this can be removed
            NODE_DIR = '/home/node/nvm'
            NVM_NODEJS_ORG_MIRROR = 'https://nexus.luigi.worldpay.io/repository/nodejs.org/'
            FIS_ARTIFACTORY_TOKEN = ''
            RUN_DEPLOY = false
            //UPLOAD_NEXUS = false
        }

        stages {
            stage('Validation and Setup') {
                steps {
                    script {
                        println(env.BUILD_VERSION)
                        validateTeamName(teamName)
                        xldEnvironmentId = determineXldEnvironmentId(teamName, params.DEPLOYMENT_ENVIRONMENT)

                        // determine deployment steps
                        RUN_XLD_DEPLOY = params.RUN_DEPLOY ?: false
                        UPLOAD_ARTIFACTS_NEXUS = params.UPLOAD_NEXUS ?: false


                        // set the github owner (Github Organizations )
                        ghOwner = sh(script: "echo ${env.GIT_URL} | sed -E 's|.*/([^/]+)/[^/]+\$|\\1|'", returnStdout: true).trim()

                        sh '''
                            echo ". /home/node/nvm/nvm.sh" >> ~/.profile
                            . /home/node/nvm/nvm.sh
                            nvm install ${NODE_VERSION}
                            nvm use ${NODE_VERSION}
                            node -v
                        '''
                        // if the current branch is not the default branch do not deploy
                        // env.BRANCH_IS_PRIMARY = null or true
                        if (env.BRANCH_IS_PRIMARY || env.BRANCH_NAME == 'master' || env.BRANCH_NAME == 'main') {
                            scanMode = 'INTELLIGENT'
                            RUN_XLD_DEPLOY = true
                            UPLOAD_ARTIFACTS_NEXUS = true
                            echo 'Is this the default integration branch? '
                        }



                        // initialize
                        initializeCommitStatuses(params, ghOwner, env.APP_NAME)
                    }
                }

                post {
                    success { echo 'Build stage - Successful'
                        addCommitStatus([ghOwner: ghOwner,
                        ghRepo: env.APP_NAME,
                        context: 'Stage/Build',
                        state: 'success',
                        description: ' stage is complete'])
                    }
                    unsuccessful { echo 'Build stage - Failure'
                        addCommitStatus([ghOwner: ghOwner,
                            ghRepo: env.APP_NAME,
                            context: 'Stage/Build',
                            state: 'failure',
                            description: ' stage failed'])
                    }
                }
            }

            stage('Create report file') {
                steps {
                    echo 'not creating report file'
                //createReportFile repo: repoName, branch: env.BRANCH_NAME
                }
            }

            stage('Build Source') {
                steps {
                    script {
                        withCredentials([string(credentialsId: 'SVC-docetartifactory-tkn', variable: 'token')]) {
                            env.FIS_ARTIFACTORY_TOKEN = "${token}"
                            def deployEnv = params.DEPLOYMENT_ENVIRONMENT.toLowerCase().replace('\n', '')
                            def packageLockExists = fileExists 'package-lock.json'

                            if (packageLockExists) {
                                sh """
                                        export FIS_ARTIFACTORY_TOKEN=${token}
                                        . /home/node/nvm/nvm.sh
                                        npm ci
                                        npm run ng -- build --configuration=${deployEnv}
                                """
                            }else {
                                sh """
                                        export FIS_ARTIFACTORY_TOKEN=${token}
                                        . /home/node/nvm/nvm.sh
                                        node -v
                                        npm i
                                        npm run ng -- build --configuration=${deployEnv}
                                    """
                            }
                            
                            // set the version with the tag versions
                            if(!params.BUILD_RELEASE){
                                // set env BUILD_TAG to version created above
                               BUILD_TAG = "${env.BUILD_VERSION}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}-devbuild"
                            }else{
                               BUILD_TAG = "${env.BUILD_VERSION}-${env.BRANCH_NAME}-${env.BUILD_NUMBER}-release"
                            }
                        }
                    }
                }
                post {
                    success { echo 'Build Source stage - Successful'
                        addCommitStatus([ghOwner: ghOwner,
                        ghRepo: env.APP_NAME,
                        context: 'Stage/Build Source',
                        state: 'success',
                        description: ' stage is complete'])
                    }
                    unsuccessful { echo 'Build Source stage - Failure'
                        addCommitStatus([ghOwner: ghOwner,
                            ghRepo: env.APP_NAME,
                            context: 'Stage/Build Source',
                            state: 'failure',
                            description: ' stage failed'])
                    }
                }
            }

            stage('Run scans'){
                parallel{

                    stage('Run npm audit'){

                        when{ expression{ params.RUN_NPM_AUDIT == true } }
                        steps{
                            //TODO: set up connection to nexus for npm audit
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                sh 'npm audit --registry=https://registry.npmjs.org'
                            }
                          
                        }
                        post {
                            success { echo 'NPM audit stage - Successful'
                                addCommitStatus([ghOwner: ghOwner,
                                ghRepo: env.APP_NAME,
                                context: 'Stage/Run NPM Audit',
                                state: 'success',
                                description: ' stage is complete'])
                            }
                            unsuccessful { echo 'NPM audit stage - Failure'
                                addCommitStatus([ghOwner: ghOwner,
                                    ghRepo: env.APP_NAME,
                                    context: 'Stage/Run NPM Audit',
                                    state: 'failure',
                                    description: ' stage failed'])
                            }
                        }
                    }

                    stage('Scan with SonarQube') {
                        when { expression { params.RUN_SONARQUBE_SCAN == true } }

                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                sonarQubeScan()
                            }
                        }

                        post {
                            success { echo 'Sonar Scan stage - Successful'
                                addCommitStatus([ghOwner: ghOwner,
                                ghRepo: env.APP_NAME,
                                context: 'Stage/SonarQube Scan',
                                state: 'success',
                                description: ' stage is complete'])
                            }
                            unsuccessful { echo 'SonarQube Scan stage - Failure'
                                addCommitStatus([ghOwner: ghOwner,
                                    ghRepo: env.APP_NAME,
                                    context: 'Stage/SonarQube Scan',
                                    state: 'failure',
                                    description: ' stage failed'])
                            }
                        }
                    }

                    stage('Checkmarx scan') {
                        when { expression { params.RUN_CHECKMARX_SCAN == true } }

                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                              checkmarxScan([projectName: checkmarxProjectName,
                                      teamPath: checkmarxTeamPath])
                            }

                            // emailext body: 'Please find attached the latest scan PDF report.',
                            //         attachmentsPattern: 'Checkmarx/Reports/**/*.pdf',
                            //         recipientProviders: [[$class: 'RequesterRecipientProvider']],
                            //         subject: 'Checkmarx scan PDF report',
                            //         to: 'collin.stolpa@fisglobal.com'
                        }

                        post {
                            success { echo 'Checkmarx Scan stage - Successful'
                                addCommitStatus([ghOwner: ghOwner,
                                ghRepo: env.APP_NAME,
                                context: 'Stage/Checkmarx Scan',
                                state: 'success',
                                description: 'Checkmarx Scan stage is complete'])
                            }
                            unsuccessful { echo 'Checkmarx Scan stage - Failure'
                                addCommitStatus([ghOwner: ghOwner,
                                    ghRepo: env.APP_NAME,
                                    context: 'Stage/Checkmarx Scan',
                                    state: 'failure',
                                    description: 'Checkmarx Scan stage failed'])
                            }
                        }
                    }

                    stage('Blackduck Scan') {
                        when { expression { params.RUN_BLACKDUCK_SCAN } }

                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                blackduckScan([
                                    version: blackduckProjectVersion,
                                    projectName: blackduckProjectName,
                                    files: blackduckFiles,
                                    url: blackduckUrl,
                                    credId: blackduckCredId,
                                    unmap: 'false',
                                    scanMode: scanMode
                                ])
                                checkBlackduckResults([
                                    scanMode: scanMode
                                ])
                            }
                        }
                        post {
                            success { echo 'Blackduck Scan stage - Successful'
                                addCommitStatus([ghOwner: ghOwner,
                                ghRepo: env.APP_NAME,
                                context: 'Stage/Blackduck Scan',
                                state: 'success',
                                description: 'Stage is complete'])
                            // sh """ echo '<font color="green">Successful Blackduck Stage</font></td></tr>' >> ${config.reportFile} """
                            }
                            unsuccessful { echo 'Blackduck Scan stage - Failure'
                                addCommitStatus([ghOwner: ghOwner,
                                    ghRepo: env.APP_NAME,
                                    context: 'Stage/Blackduck Scan',
                                    state: 'failure',
                                    description: 'Stage failed'])
                            // sh """ echo '<font color="green">Failed Blackduck stage</font></td></tr>' >> ${config.reportFile} """
                            }
                        }
                    }
                }
            }

            //TODO: get to publish
            stage('Publish to Nexus') {
                when { expression { UPLOAD_ARTIFACTS_NEXUS} }
                steps  {
                    script { 
                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            // create the BUILD_TAG.zip 
                            //sh "zip ${appName}-${BUILD_TAG}.zip dist/${appName}/*"
                            sh "(cd dist/${appName} && zip -r - *) > ${appName}-${BUILD_TAG}.zip"
                            withCredentials([usernameColonPassword(credentialsId: 'docat-us-migrator', variable: 'auth')]) {
                                // First set the Nexus artifact url in the environment variable
                                if(!params.BUILD_RELEASE){  
                                    nexusArtifactUrl = "https://nexus.luigi.worldpay.io/repository/docat-us-frontend-snapshots/${teamName}/${appName}/${BUILD_VERSION}/${appName}-${BUILD_TAG}.zip"
                                } else {
                                    nexusArtifactUrl = "https://nexus.luigi.worldpay.io/repository/docat-us-frontend-releases/${teamName}/${appName}/${BUILD_VERSION}/${appName}-${BUILD_TAG}.zip"
                                }
                                sh """
                                        curl -u '${auth}' --fail --upload-file ${appName}-${BUILD_TAG}.zip ${nexusArtifactUrl}
                                    """
                            }                        
                        }
                    }
                }

                post {
                    success { echo 'Publish to Nexus stage - Successful'
                        addCommitStatus([ghOwner: ghOwner,
                        ghRepo: env.APP_NAME,
                        context: 'Stage/Publish to Nexus',
                        state: 'success',
                        description: ' stage is complete'])
                    }
                    unsuccessful { echo 'Publish to Nexus stage - Failure'
                        addCommitStatus([ghOwner: ghOwner,
                            ghRepo: env.APP_NAME,
                            context: 'Stage/Publish to Nexus',
                            state: 'failure',
                            description: ' stage failed'])
                    }
                }
            }

            stage('Publish with Xl Deploy'){
                environment {
                    NEXUS_ARTIFACT_URL = "${nexusArtifactUrl}"
                }
                when{ expression{ RUN_XLD_DEPLOY }}
                steps { 
                    script {
                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            publishToXLD(appName, deployXmlPath)
                        }
                    }
                }
                post {
                    success { echo 'Publish with XL Deploy stage - Successful'
                        addCommitStatus([ghOwner: ghOwner,
                        ghRepo: env.APP_NAME,
                        context: 'Stage/Publish with XL Deploy',
                        state: 'success',
                        description: ' stage is complete'])
                    }
                    unsuccessful { echo 'Publish with XL Deploy stage - Failure'
                        addCommitStatus([ghOwner: ghOwner,
                            ghRepo: env.APP_NAME,
                            context: 'Stage/Publish with XL Deploy',
                            state: 'failure',
                            description: ' stage failed'])
                    }
                }
            }

            stage('Deploy to environment') {
                when { expression { RUN_XLD_DEPLOY } }
                steps {
                    script {
                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            retry(3) {

                                xldDeploy environmentId: "${xldEnvironmentId}",
                                    packageId: "Applications/${xldPackageIdSuffix}/${env.XLD_APP_VERSION}",
                                    serverCredentials: 'xld-uat'                             
                            }
                        }                    
                    } 
                }

                post {
                    success { echo 'Deploy to environment stage - Successful'
                        addCommitStatus([ghOwner: ghOwner,
                        ghRepo: env.APP_NAME,
                        context: 'Stage/Deploy to environment',
                        state: 'success',
                        description: ' stage is complete'])
                    }
                    unsuccessful { echo 'Deploy to environment stage - Failure'
                        addCommitStatus([ghOwner: ghOwner,
                            ghRepo: env.APP_NAME,
                            context: 'Stage/Deploy to environment',
                            state: 'failure',
                            description: ' stage failed'])
                    }
                }
            }

            /*stage('Qualys Security Scan') {
                steps {
                    qualysSecurityScan repo: repoName, tag: buildTag
                }
            }*/
        }

        post {
            always {
                println('el fin')
            //sendEmailNotification repo: repoName, branch: env.BRANCH_NAME, recipients: recipients, reportFile: REPORT_FILE
            }
            //TODO: return results from stage(s) error(s)
            unsuccessful {
                println('Build Failed')
            }
        }
    }
}

// Read package.json and return the version
static String getPackageVersion() {
        //return

// if(fileExists 'package.json'){
// }
// else{
//     error("package.json doesn't exist.")
// }
}

static String determineXldEnvironmentId(String teamName, String environment) {
    if ('launchpad'.equals(teamName)) {
        //TODO: when we have configured the proper naming convention for XLD folder structure
        switch (environment) {
            case 'None':
                return ''
            case 'dev':
                return 'Environments/launchpad/ui/launchpadui-dev'
            case 'test':
                return 'Environments/launchpad/ui/launchpadui-test'
            case 'cert':
                return 'Environments/launchpad/ui/launchpadui-cert'
        }
    }

    error('Unable to determine XLD environment ID')
}

static String buildXldVersion(String buildVersion, String buildNumber, String branchName, boolean isReleaseBuild) {
    if (!isReleaseBuild) {
        return buildVersion + '-' + branchName + '-' + buildNumber + '-devbuild'
    } else {
        return buildVersion + '-' + branchName + '-' + buildNumber + '-release'
    }
}

static void validateTeamName(String teamName) {
    ArrayList<String> validTeamNames = ['launchpad']

    if (!validTeamNames.contains(teamName)) {
        error('Invalid teamName passed. Valid team names: ' + validTeamNames)
    }
}
