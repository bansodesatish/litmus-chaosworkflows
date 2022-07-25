
#!/bin/sh

# for each in $(kubectl -nlitmus  get workflows -o jsonpath="{.items[*].metadata.name}");
# do
#     kubectl -nlitmus delete  workflows $each;
# done;
kubectl -nlitmus delete  workflows workflow-$BUILD_NUMBER

# for each in $(kubectl -nlitmus  get chaosresults -l workflow_name=workflow-$BUILD_NUMBER -o jsonpath="{.items[*].metadata.name}");
# do
#     kubectl -nlitmus delete chaosresults $each;
# done;

kubectl -nlitmus  delete chaosresults -l workflow_name=workflow-$BUILD_NUMBER