// Azure specific functions
// Call function: azure_functions.<function>

// Get the latest vhd url in Azure
def select_image(String project, String resource_group, String storage_account, String container) {
    try {
        withEnv(["PROJECT=$project", "SRC_STORAGE=$storage_account", "SRC_GROUP=$resource_group", "CONTAINER=$container"]) {
            sh '''
            #!/bin/bash -x
            function parse_image_list() {
                for image in $1
                do
                    # Check if the image is in uploading or leased
                    show=`az storage blob show -n $image --connection-string ${connectionstring} --container ${CONTAINER}`
                    status=`echo $show|jq .properties.copy.status -r`
                    end=`echo $show|jq '.properties.pageRanges[-1].end'`
                    lease=`echo $show|jq '.lease.state'`
                    if [ x"$status" == x"success" ]||([ x"$end" == x"10737418751" ]||[ x"$end" == x"68719477247" ])||[ x"$lease" ];then
                        break
                    fi
                done
            }

            function get_image() {
                filter="$PROJECT"
                # Generate filter list (e.g. 8.3.1 8.3 8)
                filter_list=""
                while true;
                do
                    filter_list+=" $filter"
                    [[ $filter != ${filter%.*} ]] || break
                    filter=${filter%.*}
                done
                for f in $filter_list;
                do
                    echo "Searching for the latest RHEL-${f} image"
                    # Get image
                    if [[ "$1" == "production" ]];then
                        TMP_IMAGE_LIST=$(echo $IMAGE_LIST_X|tr ' ' '\n' | grep RHEL-$f | grep -v '.n')||true
                    else
                        TMP_IMAGE_LIST=$(echo $IMAGE_LIST_X|tr ' ' '\n' | grep RHEL-$f)||true
                    fi
                    parse_image_list $TMP_IMAGE_LIST
                    [[ x"$image" == x ]] || break
                done
            }

            echo "============== Getting connectionstring... ============="
            connectionstring=`az storage account show-connection-string -n $SRC_STORAGE -g $SRC_GROUP|jq .connectionString -r`
            echo "============== Select the latest IMAGE if not specify ============="
            # Get RHEL x version
            x_version=${PROJECT//.*}
            y_version=$(echo ${PROJECT} | cut -d'.' -f2)
            # Filter image
            # Get RHEL version filter
            IMAGE_LIST_X=$(az storage blob list --connection-string $connectionstring --container ${CONTAINER}|jq .[].name -r|grep "RHEL-${x_version}"|grep -v "updates"|sort -rV)||true
            # Parse the list in order e.g. if no 8.3.1, search for 8.3, then search for 8
            image=""
            [[ x"$COMPOSE_TYPE" != x"production" ]] || get_image "production"
            [[ x"$image" != x ]] || get_image "nightly"
            [[ x"$image" != x ]] || {
                echo "No RHEL-${x_version} image found!"
                exit 1
            }
            # Verify base image version lower than target version(e.g. target is 9.0, base image should not be 9.3)
            image_y=$(echo ${image}|cut -d'.' -f2)
            if [ -n "$y_version" ];then
                [ $image_y -le $y_version ] || {
                    echo "Base image version $image higher than target project $PROJECT! Exit."
                    exit 1
                }
            fi

            url=`az storage blob url -n $image --connection-string ${connectionstring} --container ${CONTAINER}|tr -d '"'`
            echo "$url" > $WORKSPACE/ori_imageurl
            '''
        }
        return readFile('ori_imageurl').trim()
    } catch(e) {
        return ''
    }
}

// Get the latest gallery image version
// ARM64/CVM/TrustedLaunch VM can only use gallery image now
def select_image_gallery(String project, String resource_group, String gallery, String image_definition) {
    try {
        withEnv(["PROJECT=$project", "SRC_GROUP=$resource_group", "GALLERY=$gallery", "IMAGE_DEFINITION=$image_definition"]) {
            sh '''
            #!/bin/bash -x
            function parse_gallery_image_list() {
                for image in $1
                do
                    # Check if the gallery image version exists and status is Succeeded
                    show=$(az sig image-version show -g $SRC_GROUP --gallery-image-version $image \
                        --gallery-image-definition ${IMAGE_DEFINITION} --gallery-name ${GALLERY})
                    status=$(echo $show|jq .provisioningState -r)
                    if [ x"$status" == x"Succeeded" ]; then
                        break
                    fi
                done
            }

            function get_image() {
                filter="$PROJECT"
                # Generate filter list (e.g. 8.7 8)
                filter_list=""
                while true;
                do
                    filter_list+=" $filter"
                    [[ $filter != ${filter%.*} ]] || break
                    filter=${filter%.*}
                done
                for f in $filter_list;
                do
                    echo "Searching for the latest RHEL-${f} gallery image version..."
                    # Get image
                    TMP_IMAGE_LIST=$(echo $IMAGE_LIST_X|tr ' ' '\n' | grep ^$f)||true
                    parse_gallery_image_list $TMP_IMAGE_LIST
                    [[ x"$image" == x ]] || break
                done
            }

            echo "============== Select the latest IMAGE if not specify ============="
            # Get RHEL x version
            x_version=${PROJECT//.*}
            y_version=$(echo ${PROJECT} | cut -d'.' -f2)
            # Filter image
            # Get RHEL version filter
            IMAGE_LIST_X=$(az sig image-version list -g ${SRC_GROUP} --gallery-name ${GALLERY} --gallery-image-definition ${IMAGE_DEFINITION}|jq -r .[].name|grep -v ^0|sort -rV)||true
            # Parse the list
            image=""
            get_image
            [[ x"$image" != x ]] || {
                echo "No RHEL-${x_version} gallery image version found!"
                exit 1
            }
            # Verify base image version lower than target version(e.g. target is 9.0, base image should not be 9.3)
            image_y=$(echo ${image}|cut -d'.' -f2)
            if [ -n "$y_version" ];then
                [ $image_y -le $y_version ] || {
                    echo "Base image version $image higher than target project $PROJECT! Exit."
                    exit 1
                }
            fi

            echo "$image" > $WORKSPACE/ori_image_version
            '''
        }
        return readFile('ori_image_version').trim()
    } catch(e) {
        return ''
    }
}

def getConnectionString(String sa_name, String rg_name) {
    return sh(script: "az storage account show-connection-string -g ${rg_name} -n ${sa_name}|jq .connectionString -r", returnStdout: true).trim()
}

def create_image_version(String image_version) {
    // For ARM64/CVM/TrustedLaunch, verify and create gallery image-version
    // Must have environment vars: GALLERY, IMAGE_DEFINITION, SRC_GROUP, SRC_STORAGE, CONTAINER, AZURE_SUBSCRIPTION, TESTIMAGE
    withEnv(["IMAGE_VERSION=$image_version"]) {
        sh '''
        # "For ARM64/CVM/TrustedLaunch, verify image-version exists..."
        state=$(az sig image-version show -g ${SRC_GROUP} --gallery-image-version ${IMAGE_VERSION} \
            --gallery-image-definition ${IMAGE_DEFINITION} --gallery-name ${GALLERY} | jq -r .provisioningState)
        if [[ x$state != 'xSucceeded' ]]; then
            # If can get state and not succeed, delete the old image-version first
            [[ x$state != x ]] && {
                echo "image-version ${IMAGE_VERSION} can get state but not Succeeded! Delete the old one." 
                az sig image-version delete --resource-group ${SRC_GROUP} \
                    --gallery-name ${GALLERY} --gallery-image-definition ${IMAGE_DEFINITION} \
                    --gallery-image-version ${IMAGE_VERSION}
            }

            # Create gallery and image-definition
            az sig image-version show -g ${SRC_GROUP} --gallery-image-version ${IMAGE_VERSION} \
                --gallery-image-definition ${IMAGE_DEFINITION} --gallery-name ${GALLERY} || {
                # Create image-version
                az sig image-version create --resource-group ${SRC_GROUP} \
                    --gallery-name ${GALLERY} --gallery-image-definition ${IMAGE_DEFINITION} \
                    --gallery-image-version ${IMAGE_VERSION} \
                    --os-vhd-storage-account /subscriptions/${AZURE_SUBSCRIPTION}/resourceGroups/${SRC_GROUP}/providers/Microsoft.Storage/storageAccounts/${SRC_STORAGE} \
                    --os-vhd-uri https://${SRC_STORAGE}.blob.core.windows.net/${CONTAINER}/${TESTIMAGE}
                [[ $? == 0 ]] || {
                    echo "Create image-version failed! Exit."
                    exit 1
                }
                echo "Create image-version successfully!"
            }
        else
            echo "image-version already exists."
        fi
        '''
    }
}
