#!/usr/bin/env groovy
def colors = [
    SUCCESS: 'good',
    FAILURE: '#e81f3f',
    ABORTED: 'warning',
    UNSTABLE: 'warning'
]

def slackChannel = 'chaos'
def decodedJobName = env.JOB_NAME.replaceAll('%2F', '/')
def chaosResults = ""
def chaosResult = ""
pipeline {
    environment {
		DOCKERHUB_CREDENTIALS_DEV=credentials('bansodesatish24')
		DOCKERHUB_CREDENTIALS_PROD=credentials('sat30bansode24')
		LITMUS_CREDENTIALS=credentials('litmus')
        DOCKER_DEV_PATH = "bansodesatish24/dev"
        DOCKER_PROD_PATH = "sat30bansode24/prod"
        DOCKER_IMAGE_PREFIX = "chaoscarnival-demo"
	}
    agent {
        kubernetes {
            label 'kube-agent'
            yaml '''
            apiVersion: v1
            kind: Pod
            spec:
                serviceAccountName: jenkins
                containers:
                - name: chaos-builder
                  image: bansodesatish24/builder-base:0.1.275v3
                  command:
                  - cat
                  tty: true
                  volumeMounts:
                  - name: docker
                    mountPath: /var/run/docker.sock
                volumes:
                - name: docker
                  hostPath:
                    path: /var/run/docker.sock
            '''
        }
    }

    stages {
        stage('Prepare env ') {
            steps {
                container('chaos-builder') {
                    script {
                            DATE_VERSION = new Date().format('yyyyMMdd')
                            VERSION_SUFFIX = 'demo'
                            VERSION_SUFFIX = "${VERSION_SUFFIX}-BUILD-${BUILD_NUMBER}"
                            env.DOCKER_IMAGE_TAG = "${VERSION_SUFFIX}"
                            env.APP_DOCKER_IMAGE_DEV = "${DOCKER_DEV_PATH}-chaoscarnival-demo:${DOCKER_IMAGE_TAG}"
                            env.APP_DOCKER_IMAGE_PROD = "${DOCKER_PROD_PATH}-chaoscarnival-demo:${DOCKER_IMAGE_TAG}"         
                            triggerDesc = currentBuild.getBuildCauses().get(0).shortDescription
                            slackSend (
                                channel: "${slackChannel}",
                                attachments: [[
                                    title: "${decodedJobName}, build #${BUILD_NUMBER}",
                                    title_link: "${env.BUILD_URL}",
                                    color: '#11aac4',
                                    text: "Started",
                                    fields: [
                                        [
                                            title: "Trigger",
                                            value: "${triggerDesc}",
                                            short: true
                                        ]
                                    ]
                                ]]
                            )
                        }
                }
                
            }
        }
        stage('Build image and push it to dev') {
            steps {
                container('chaos-builder') {
                    sh '''
                    echo $DOCKERHUB_CREDENTIALS_DEV_PSW | docker login -u $DOCKERHUB_CREDENTIALS_DEV_USR --password-stdin
                    cd app 
                    docker build \
                            --network=host \
                            --tag ${APP_DOCKER_IMAGE_DEV} .
                    docker push ${APP_DOCKER_IMAGE_DEV}
                    sed --help
                    awk --help
                    echo "ENV: ${ENV}"
                    '''
                    
                }  
            }
        }
        stage('Deploying Sample App') {
            steps {
                container('chaos-builder') {  
                    sh '''
                    echo ""
                    kubectl create ns app -o yaml --dry-run=true | kubectl apply -f -
                    kubectl create secret  -n app docker-registry regcred --docker-server=https://index.docker.io/v1/ --docker-username=$DOCKERHUB_CREDENTIALS_DEV_USR --docker-password=$DOCKERHUB_CREDENTIALS_DEV_PSW  --dry-run=true -o yaml | kubectl apply -f -
                    echo ""
                    cat ./manifests/app/app.yml | sed "s|{{DOCKER_IMAGE}}|$APP_DOCKER_IMAGE_DEV|" | sed "s|{{REGISTRY_PULL_SECRET}}|regcred|" | kubectl apply -f -
                    kubectl wait --for=condition=available --timeout=600s deployment/${DOCKER_IMAGE_PREFIX} -n app
                    '''
                }  
            }
        }
        stage('Injecting chaos') {
            steps {
                container('chaos-builder') {  
                    sh '''
                    echo "wait 60 Sec..............."
                    sleep 60;
                    echo "Litmus-user: "$LITMUS_CREDENTIALS_USR
                    echo "Litmus-psw: "$LITMUS_CREDENTIALS_PSW
                    echo "Configure litmusctl"
                    litmusctl config set-account --endpoint="http://litmusportal-frontend-service.litmus:9091/" --username="$LITMUS_CREDENTIALS_USR" --password="$LITMUS_CREDENTIALS_PSW"

                    LITMUS_WORKFLOW_AGENT_ID=$(litmusctl get agents --project-id="$(litmusctl get projects  -o yaml | yq '.[0].id' -)" -o yaml | yq '.getagent[0].clusterid' -)

                    
                    echo "Deploy workflow from yaml file"
                    cat workflows/workflow.yaml | sed "s|{{WORKFLOW_ID}}|$BUILD_NUMBER|" | sed "s|{{LITMUS_WORKFLOW_AGENT_ID}}|$LITMUS_WORKFLOW_AGENT_ID|" | kubectl apply -f -
                    '''
                }  
            }
        }
        stage('Waiting chaos to finish') {
            steps {
                container('chaos-builder') {  
                    sh '''
                    set -x
                    until kubectl get workflow workflow-$BUILD_NUMBER -o jsonpath='{.metadata.labels.\\workflows\\.argoproj\\.io\\/phase}' -nlitmus | grep -m 1 "Succeeded\\|Failed";
                    do
                    echo "waiting.. for the \"workflow-$BUILD_NUMBER\" chaos workflow to finish";
                    done
                    set +x
                    sleep 10;
                    '''
                }  
            }
        }
        stage('Collecting chaos result') {
            steps {
                container('chaos-builder') {  
                    sh '''
                    echo "Getting.. \"workflow-$BUILD_NUMBER\" chaos workflow result"
                    chaosResults=$(kubectl get $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER) -o jsonpath='{"ExperimentName: "}{.metadata.labels.name}{"; verdict: "}{.status.experimentStatus.verdict}{"; Resilience Score: "}{.status.experimentStatus.probeSuccessPercentage}{" || "}' -nlitmus);
                    echo $chaosResults >> report.txt;

                    kubectl get $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER) -o jsonpath='{.status.experimentStatus.probeSuccessPercentage}' -n litmus >> probeSuccessPercentage.txt
                    kubectl get  $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER) -o jsonpath='{.status.experimentStatus.verdict}' -n litmus >> verdict.txt
                    '''
                }  
            }
        }
        stage('Processing Chaos Result') {
            steps {
                script {
                    chaosResults  = readFile('report.txt').trim()
                    chaosResult=sh returnStdout: true, script: 'grep -q "Pass" report.txt; test $? -eq 0 && printf "Pass" || printf "Fail"'
                    probeSuccessPercentage =  readFile('probeSuccessPercentage.txt')                    
                    chaosStatus =  readFile('verdict.txt')
                }
            }
        }
        stage('Promoting image to Prod') {
            when {
                expression { chaosResult == 'Pass' }
            }
            steps {
                container('chaos-builder') {
                    sh '''
                    echo $DOCKERHUB_CREDENTIALS_PROD_PSW | docker login -u $DOCKERHUB_CREDENTIALS_PROD_USR --password-stdin
                    docker tag ${APP_DOCKER_IMAGE_DEV} ${APP_DOCKER_IMAGE_PROD} 
                    docker push ${APP_DOCKER_IMAGE_PROD} 
                    '''
                    
                }  
            }

        }
        stage('Clean Up') {
            when {
                expression { chaosResult == 'Pass' }
            }
            steps {
                container('chaos-builder') {
                    sh '''
                    ./scripts/cleanup.sh
                    '''
                    
                }  
            }
        }
    }
    post {
        always {
            script {
                triggerDesc = currentBuild.getBuildCauses().get(0).shortDescription
                attachments = [
                    [
                        title: "${env.JOB_NAME}, build #${env.BUILD_NUMBER}",
                        title_link: "${env.BUILD_URL}",
                        color: colors[currentBuild.result],
                        text: "${currentBuild.result}",
                        fields: [
                            [
                                title: "Trigger",
                                value: "${triggerDesc}",
                                short: true
                            ],
                            [
                                title: "Chaos Percentage",
                                value: "${probeSuccessPercentage}",
                                short: true
                            ],
                            [
                                title: "chaos Status",
                                value: "${chaosStatus}",
                                short: true
                            ],
                            [
                                title: "Duration",
                                value: "${currentBuild.durationString}",
                                short: true
                            ]
                        ]
                    ]
                ]
            }
        }

        success {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }

        unstable {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }

        failure {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }

        aborted {
            script {
                slackSend (
                    channel: "${slackChannel}",
                    attachments: attachments
                )
            }
        }
    }
}