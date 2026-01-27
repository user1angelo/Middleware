#!/bin/bash
#
# Installs OpenDaylight Carbon dependencies from a local distribution into the local Maven repository.
# Usage: ./install_odl_deps.sh /path/to/distribution-karaf-0.6.4-Carbon
#

ODL_PATH="$1"

if [ -z "$ODL_PATH" ]; then
    echo "Usage: $0 /path/to/distribution-karaf-0.6.4-Carbon"
    exit 1
fi

if [ ! -d "$ODL_PATH" ]; then
    echo "Error: Path not found: $ODL_PATH"
    exit 1
fi

echo "Searching for artifacts in $ODL_PATH..."

# Define artifacts to install
declare -A ARTIFACTS
ARTIFACTS["org.opendaylight.mdsal:mdsal-binding-api:1.6.4-Carbon"]="mdsal-binding-api"
ARTIFACTS["org.opendaylight.mdsal:mdsal-common-api:1.6.4-Carbon"]="mdsal-common-api"
ARTIFACTS["org.opendaylight.controller:sal-binding-api:1.6.4-Carbon"]="sal-binding-api"
ARTIFACTS["org.opendaylight.openflowplugin:openflowplugin-api:0.5.4-Carbon"]="openflowplugin-api"

install_artifact() {
    local GROUP_ID="$1"
    local ARTIFACT_ID="$2"
    local VERSION="$3"
    local SEARCH_NAME="$4"
    
    echo ""
    echo "Looking for $ARTIFACT_ID..."
    
    # Find the JAR file
    JAR_FILE=$(find "$ODL_PATH" -name "${SEARCH_NAME}*.jar" -type f 2>/dev/null | head -1)
    
    if [ -n "$JAR_FILE" ]; then
        echo "  Found: $JAR_FILE"
        echo "  Installing as $GROUP_ID:$ARTIFACT_ID:$VERSION..."
        
        mvn install:install-file \
            -Dfile="$JAR_FILE" \
            -DgroupId="$GROUP_ID" \
            -DartifactId="$ARTIFACT_ID" \
            -Dversion="$VERSION" \
            -Dpackaging=jar \
            -DgeneratePom=true
        
        if [ $? -eq 0 ]; then
            echo "  ✓ Successfully installed $ARTIFACT_ID"
        else
            echo "  ✗ Failed to install $ARTIFACT_ID"
        fi
    else
        echo "  ✗ Could not find JAR for $ARTIFACT_ID"
    fi
}

# Install each artifact
install_artifact "org.opendaylight.mdsal" "mdsal-binding-api" "1.6.4-Carbon" "mdsal-binding-api"
install_artifact "org.opendaylight.mdsal" "mdsal-common-api" "1.6.4-Carbon" "mdsal-common-api"
install_artifact "org.opendaylight.controller" "sal-binding-api" "1.6.4-Carbon" "sal-binding-api"
install_artifact "org.opendaylight.openflowplugin" "openflowplugin-api" "0.5.4-Carbon" "openflowplugin-api"

echo ""
echo "Done. Now run: mvn clean install -U"
