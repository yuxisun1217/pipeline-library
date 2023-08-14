/////////////////////////////////////////
// 
// Send umb message function. For gating test.
//
// Need to prepare ci_message_env.yaml, e.g.:
//
// ci_message_env.yaml:
//  BREW_TASKID: 54596931
//  NVR: cloud-init-23.1.1-8.el9.anisinha202308111326
//  VERSION: 23.1.1
//  RELEASE: 8.el9.anisinha202308111326
//  URL: https://download.eng.bos.redhat.com/brewroot/work/tasks/6940/54596940/cloud-init-23.1.1-8.el9.anisinha202308111326.noarch.rpm
//  ARCH: noarch
//  PKGNAME: cloud-init
//  OWNER: anisinha
//  OS: rhel-9.3.0
//  SCRATCH: true
//  
//  NAMESPACE: 3rd-azure-ci
//  EMAIL: yuxisun@redhat.com
//  NAME: AZURE-CI
//  PROVIDER: <Automation framework. e.g. LISAv2>
//  TESTSUITE: tier1
//  CHANNEL: <your VirtualTopic channel, e.g. 3rd-ci>
//
//  HTMLURL: <Test result html file link>
//  TESTRESULT: passed/failed
/////////////////////////////////////////

def send_test_message(String message_type) {
    ci = readYaml file: 'ci_message_env.yaml'
    String date = sh(script: 'date -u +"%Y-%m-%dT%H:%M:%SZ"', returnStdout: true).trim()
    def thread_id = sh(script: "echo ${ci.BREW_TASKID} | md5sum | awk '{print \$1}'", returnStdout: true).trim()
    thread_id = thread_id + '-gating'
    def scratch = ''
    if ( "${ci.SCRATCH}" == 'true' ) {
        scratch = true
    } else {
        scratch = false
    }
    def reason = null
    def note = null
    if ( message_type == 'error' ) {
        note = "error"
        reason = 'Triggered but hit unexpected error'
    }
    // UMB message body
    def message_map = [:]
    message_map.artifact = [
        "type": "brew-build",
        "id": ci.BREW_TASKID,
        "issuer": "${ci.OWNER}",
        "nvr": "${ci.NVR}",
        "component": "${ci.PKGNAME}",
        "scratch": scratch
    ]
    message_map.contact = [
        "docs": "${ci.DOCS}",
        "url": "${env.JENKINS_URL}",
        "team": "VirtQE-S1",
        "irc": "#S1",
        "email": "${ci.EMAIL}",
        "name": "${ci.NAME}"
    ]
    message_map.generated_at = "${date}"
    message_map.pipeline = [
        "id": "${ci.BREW_TASKID}-gating",
        "name": "Tier1 Gating"
    ]
    message_map.run = [
        "log": "${ci.BUILDURL}console",
        "url": "${ci.BUILDURL}",
        "debug": "${ci.HTMLURL}",
        "rebuild": "${ci.BUILDURL}rebuild"]
    message_map.system = [[
        "os": "${ci.OS}",
        "architecture": "${ci.ARCH}",
        "provider": "${ci.PROVIDER}"
    ]]
    message_map.test = [
        "category": "functional",
        "namespace": "${ci.NAMESPACE}.brew-build",
        "type": "${ci.TESTSUITE}",
        "result": "${ci.TESTRESULT}"
    ]
    message_map.version = "1.1.14"

    message_content = JsonOutput.toJson(message_map)
    echo "${message_content}"
    def ret = sendCIMessage (
        providerName: 'Red Hat UMB',
        overrides: [topic: "VirtualTopic.eng.ci.${ci.CHANNEL}.brew-build.test.${message_type}"],
        messageContent: message_content,
        messageType: 'Custom',
        failOnError: true
    )
    String id = ret.getMessageId()
    String content = ret.getMessageContent()
    echo "${id}"
    echo "${content}"
    def STATUSCODE = sh (returnStdout: true, script: """
        curl --insecure --silent --output /dev/null --write-out "%{http_code}" "https://datagrepper.engineering.redhat.com/id?id=${id}&chrome=false&is_raw=false"
        """).trim()
    echo "${STATUSCODE}"
    if (STATUSCODE.equals("404")) {
        error("message not found on datagrepper...")
    }
    if (STATUSCODE.startsWith("5")) {
        echo("WARNING: internal datagrepper server error...")
    } else {
        echo "Sending CI message ${message_type} successfully!"
    }
}
