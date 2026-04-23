#!/usr/bin/env bash

source env.bash

CLIENT_REDIRECT_URI=https://my.redirect.uri
API_NAME=product-composite
API_URL=https://localhost:8443/product-composite

# set -x
set -euo pipefail

auth0_api() {
  local method=$1
  local url=$2
  local data=${3:-}
  local raw_response
  local response
  local http_code

  if [ -n "$data" ]; then
    raw_response=$(curl -s -H "Authorization: Bearer $AT" -X "$method" -H "Content-Type: application/json" -d "$data" -w $'\n%{http_code}' "$url")
  else
    raw_response=$(curl -s -H "Authorization: Bearer $AT" -X "$method" -w $'\n%{http_code}' "$url")
  fi

  http_code=$(echo "$raw_response" | tail -n1)
  response=$(echo "$raw_response" | sed '$d')

  if [ "$http_code" -ge 400 ]; then
    echo "Auth0 API call failed for $method $url (HTTP $http_code)" >&2
    echo "$response" | jq . >&2
    return 1
  fi

  echo "$response"
}

TOKEN_RESPONSE=$(curl -s --request POST \
  --url https://$TENANT/oauth/token \
  --header 'content-type: application/json' \
  --data "{\"client_id\":\"$MGM_CLIENT_ID\",\"client_secret\":\"$MGM_CLIENT_SECRET\",\"audience\":\"https://$TENANT/api/v2/\",\"grant_type\":\"client_credentials\"}")
AT=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$AT" ]; then
  echo "$TOKEN_RESPONSE" | jq .
  echo "Failed to get Management API token. Check tenant/domain, client id/secret, and M2M authorization." >&2
  exit 1
fi

# Update the tenant
echo "Update the tenant, set its default connection to a user dictionary..."
TENANT_PATCH_RESPONSE=$(auth0_api PATCH "https://$TENANT/api/v2/tenants/settings" '{"default_directory":"Username-Password-Authentication"}')
echo "$TENANT_PATCH_RESPONSE" | jq -r '.default_directory // "default_directory not returned by API response"'

# Create reader application
createClientBody='"callbacks":["https://my.redirect.uri"],"app_type":"non_interactive","grant_types":["authorization_code","implicit","refresh_token","client_credentials","password","http://auth0.com/oauth/grant-type/password-realm"],"oidc_conformant":true,"token_endpoint_auth_method":"client_secret_post"'
if [ -n "$(curl -s -H "Authorization: Bearer $AT" "https://$TENANT/api/v2/clients?fields=name" | jq -r 'if type == "array" then . else (.clients // []) end | .[]? | select(.name == "reader") | .name')" ]
then
  echo "Reader client app already exists"
else
  echo "Creates reader client app..."
  auth0_api POST "https://$TENANT/api/v2/clients" "{\"name\":\"reader\",$createClientBody}" | jq .
fi
READER_CLIENT_ID=$(curl -s -H "Authorization: Bearer $AT" https://$TENANT/api/v2/clients | jq -r 'if type == "array" then . else (.clients // []) end | .[]? | select(.name == "reader") | .client_id')
READER_CLIENT_SECRET=$(curl -s -H "Authorization: Bearer $AT" https://$TENANT/api/v2/clients | jq -r 'if type == "array" then . else (.clients // []) end | .[]? | select(.name == "reader") | .client_secret')

# Create writer application
if [ -n "$(curl -s -H "Authorization: Bearer $AT" "https://$TENANT/api/v2/clients?fields=name" | jq -r 'if type == "array" then . else (.clients // []) end | .[]? | select(.name == "writer") | .name')" ]
then
  echo "Writer client app already exists"
else
  echo "Creates writer client app..."
  auth0_api POST "https://$TENANT/api/v2/clients" "{\"name\":\"writer\",$createClientBody}" | jq .
fi
WRITER_CLIENT_ID=$(curl -s -H "Authorization: Bearer $AT" https://$TENANT/api/v2/clients | jq -r 'if type == "array" then . else (.clients // []) end | .[]? | select(.name == "writer") | .client_id')
WRITER_CLIENT_SECRET=$(curl -s -H "Authorization: Bearer $AT" https://$TENANT/api/v2/clients | jq -r 'if type == "array" then . else (.clients // []) end | .[]? | select(.name == "writer") | .client_secret')

# Sleep 1 sec to avoid a "429: Too Many Requests, global limit has been reached"...'
sleep 1

# Create the API
if [ -n "$(curl -s -H "Authorization: Bearer $AT" "https://$TENANT/api/v2/resource-servers" | jq -r "if type == \"array\" then . else (.resource_servers // []) end | .[]? | select(.name == \"$API_NAME\") | .name")" ]
then
  echo "API $API_NAME ($API_URL) already exists"
else
  echo "Creates API $API_NAME ($API_URL)..."
  auth0_api POST "https://$TENANT/api/v2/resource-servers" "{\"name\": \"$API_NAME\", \"identifier\": \"$API_URL\", \"scopes\": [{ \"value\": \"product:read\", \"description\": \"Read product information\"}, {\"value\": \"product:write\", \"description\": \"Update product information\"}]}" | jq .
fi

# Create the user
if [ -n "$(curl -s -H "Authorization: Bearer $AT" --get --data-urlencode "email=$USER_EMAIL" https://$TENANT/api/v2/users-by-email | jq -r "if type == \"array\" then . else (.users // []) end | .[]? | select(.email == \"$USER_EMAIL\") | .email")" ]
then
  echo "User with email $USER_EMAIL already exists"
else
  echo "Creates user with email $USER_EMAIL..."
  auth0_api POST "https://$TENANT/api/v2/users" "{\"email\": \"$USER_EMAIL\",  \"connection\": \"Username-Password-Authentication\", \"password\": \"$USER_PASSWORD\"}" | jq .
fi


# Grant access to the API for the reader client app
clientGrantsCount=$(curl -s -H "Authorization: Bearer $AT" --get --data-urlencode "audience=$API_URL" --data-urlencode "client_id=$READER_CLIENT_ID" https://$TENANT/api/v2/client-grants | jq length)
if [[ "$clientGrantsCount" -ne 0 ]]
then
  echo "Client grant for the reader app to access the $API_NAME API already exists"
else
  echo "Create client grant for the reader app to access the $API_NAME API..."
  auth0_api POST "https://$TENANT/api/v2/client-grants" "{\"client_id\":\"$READER_CLIENT_ID\",\"audience\":\"$API_URL\",\"scope\":[\"product:read\"]}" | jq .
  echo
fi

# Grant access to the API for the writer client app
clientGrantsCount=$(curl -s -H "Authorization: Bearer $AT" --get --data-urlencode "audience=$API_URL" --data-urlencode "client_id=$WRITER_CLIENT_ID" https://$TENANT/api/v2/client-grants | jq length)
if [[ "$clientGrantsCount" -ne 0 ]]
then
  echo "Client grant for the writer app to access the $API_NAME API already exists"
else
  echo "Create client grant for the writer app to access the $API_NAME API..."
  auth0_api POST "https://$TENANT/api/v2/client-grants" "{\"client_id\":\"$WRITER_CLIENT_ID\",\"audience\":\"$API_URL\",\"scope\":[\"product:read\",\"product:write\"]}" | jq .
  echo
fi

# Echo Auth0 - OAuth2 settings
echo
echo "Auth0 - OAuth2 settings:"
echo
echo export TENANT=$TENANT
echo export WRITER_CLIENT_ID=$WRITER_CLIENT_ID
echo export WRITER_CLIENT_SECRET=$WRITER_CLIENT_SECRET
echo export READER_CLIENT_ID=$READER_CLIENT_ID
echo export READER_CLIENT_SECRET=$READER_CLIENT_SECRET
