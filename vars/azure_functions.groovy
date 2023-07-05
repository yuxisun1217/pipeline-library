// Call function: azure_functions.<function>

// Get the latest vhd name in Azure
def azure_select_image(String project, String resource_group, String storage_account, String container) {
    try {
        withEnv(["project=$project", "SRC_STORAGE=$storage_account", "SRC_GROUP=$resource_group", "CONTAINER=$container"]) {
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
                filter="$project"
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
            x_version=${project//.*}
            y_version=$(echo ${project} | cut -d'.' -f2)
            # Filter image
            # Get RHEL version filter
            IMAGE_LIST_X=$(az storage blob list --connection-string $connectionstring --container ${CONTAINER}|jq .[].name -r|grep "RHEL-${x_version}"|grep -v "updates"|sort -rn)||true
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
            [ $image_y -le $y_version ] || {
                echo "Base image version $image higher than target project $project! Exit."
                exit 1
            }

            url=`az storage blob url -n $image --connection-string ${connectionstring} --container ${CONTAINER}|tr -d '"'`
            echo "$url" > $WORKSPACE/ori_imageurl
            '''
        }
        return readFile('ori_imageurl').trim()
    } catch(e) {
        return ''
    }
}