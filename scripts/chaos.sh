#!/bin/sh

echo "wait 60 Sec..............."
sleep 60;
echo "Litmus-user: "$LITMUS_CREDENTIALS_USR
echo "Litmus-psw: "$LITMUS_CREDENTIALS_PSW
echo "Configure litmusctl"
litmusctl config set-account --endpoint="http://litmusportal-frontend-service.litmus:9091/" --username="$LITMUS_CREDENTIALS_USR" --password="$LITMUS_CREDENTIALS_PSW"
# litmusctl config set-account --endpoint="http://172.105.46.172:9091/" --username="$LITMUS_CREDENTIALS_USR" --password="$LITMUS_CREDENTIALS_PSW"

LITMUS_WORKFLOW_AGENT_ID=$(litmusctl get agents --project-id="$(litmusctl get projects  -o yaml | yq '.[0].id' -)" -o yaml | yq '.getagent[0].clusterid' -)

cat workflows/new.yaml | sed "s|{{WORKFLOW_ID}}|$BUILD_NUMBER|" | sed "s|{{LITMUS_WORKFLOW_AGENT_ID}}|$LITMUS_WORKFLOW_AGENT_ID|"

echo "Deploy workflow from yaml file"
cat workflows/new.yaml | sed "s|{{WORKFLOW_ID}}|$BUILD_NUMBER|" | sed "s|{{LITMUS_WORKFLOW_AGENT_ID}}|$LITMUS_WORKFLOW_AGENT_ID|" | kubectl apply -f -
# kubectl apply -f workflows/devoxx-delete.yml

# until kubectl get workflow  --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.labels.\workflows\.argoproj\.io\/phase}' -nlitmus | grep -m 1 "Succeeded\|Failed";
set -x
until kubectl get workflow workflow-$BUILD_NUMBER -o jsonpath='{.metadata.labels.\workflows\.argoproj\.io\/phase}' -nlitmus | grep -m 1 "Succeeded\|Failed";
do
  echo "waiting.. for the \"workflow-$BUILD_NUMBER\" chaos workflow to finish";
done
set +x
sleep 10;

# for each in $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER);
# do
#     chaosResults=$(kubectl get $each -o jsonpath='{"ExperimentName: "}{.metadata.labels.name}{"; verdict: "}{.status.experimentStatus.verdict}{"; Resilience Score: "}{.status.experimentStatus.probeSuccessPercentage}{" || "}' -nlitmus);
#     echo $chaosResults >> report.txt;
# done;
echo "Getting.. \"workflow-$BUILD_NUMBER\" chaos workflow result"
# chaosresultName=$(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER)
chaosResults=$(kubectl get $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER) -o jsonpath='{"ExperimentName: "}{.metadata.labels.name}{"; verdict: "}{.status.experimentStatus.verdict}{"; Resilience Score: "}{.status.experimentStatus.probeSuccessPercentage}{" || "}' -nlitmus);
echo $chaosResults >> report.txt;

kubectl get chaosresult $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER) -o jsonpath='{.status.experimentStatus.probeSuccessPercentage}' -n litmus >> probeSuccessPercentage.txt
kubectl get chaosresult $(kubectl get chaosresult -nlitmus --no-headers -oname -l workflow_name=workflow-$BUILD_NUMBER) -o jsonpath='{.status.experimentStatus.verdict}' -n litmus >> verdict.txt

