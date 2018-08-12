#!/bin/bash

set -o allexport
source .env
set +o allexport

scope_name="storage_scope"

# Create scope, if not exists
if [[ -z $(databricks secrets list-scopes | grep "$scope_name") ]]; then
    echo "Creating secrets scope: $scope_name"
    databricks secrets create-scope --scope "$scope_name"
fi

# Create secrets
echo "Creating secrets within scope $scope_name..."
databricks secrets write --scope "$scope_name" --key "storage_account" --string-value  "$BLOB_STORAGE_ACCOUNT"
databricks secrets write --scope "$scope_name" --key "storage_key" --string-value  "$BLOB_STORAGE_KEY"
databricks secrets write --scope "$scope_name" --key "eventhub_namespace" --string-value  "$EVENTHUB_NAMESPACE"
databricks secrets write --scope "$scope_name" --key "eventhub" --string-value  "$EVENTHUB"
databricks secrets write --scope "$scope_name" --key "eventhub_send_key" --string-value  "$EVENTHUB_SEND_KEY"
databricks secrets write --scope "$scope_name" --key "eventhub_listen_key" --string-value  "$EVENTHUB_LISTEN_KEY"
