/////////////////////////////////////////
// 
// Send umb message function. For gating test.
//
// Need to prepare umb.yaml, e.g.:
//
// umb.yaml:
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
//  TEAM: <your team, e.g. RHEL-on-Azure>
//  CHANNEL: <your VirtualTopic channel, e.g. 3rd-ci>
//  DOCS: <CI doc, e.g.https://docs.engineering.redhat.com/display/HYPERVTEST/Gating+Test>
//
//  HTMLURL: <Test result html file link>
//  TESTRESULT: passed/failed
/////////////////////////////////////////

import groovy.json.JsonOutput

def call(String message_type, String filename='umb.yaml') {
    umb = readYaml file: filename
    String date = sh(script: 'date -u +"%Y-%m-%dT%H:%M:%SZ"', returnStdout: true).trim()
    def thread_id = sh(script: "echo ${umb.BREW_TASKID} | md5sum | awk '{print \$1}'", returnStdout: true).trim()
    thread_id = thread_id + '-gating'
    def scratch = ''
    if ( "${umb.SCRATCH}" == 'true' ) {
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
        "id": umb.BREW_TASKID,
        "issuer": "${umb.OWNER}",
        "nvr": "${umb.NVR}",
        "component": "${umb.PKGNAME}",
        "scratch": scratch
    ]
    message_map.contact = [
        "docs": "${umb.DOCS}",
        "url": "${env.JENKINS_URL}",
        "team": "${umb.TEAM}",
        "irc": "#S1",
        "email": "${umb.EMAIL}",
        "name": "${umb.NAME}"
    ]
    message_map.generated_at = "${date}"
    message_map.pipeline = [
        "id": "${umb.BREW_TASKID}-gating",
        "name": "Tier1 Gating"
    ]
    message_map.run = [
        "log": "${env.BUILD_URL}console",
        "url": "${env.BUILD_URL}",
        "debug": "${umb.HTMLURL}",
        "rebuild": "${env.BUILD_URL}rebuild"]
    message_map.system = [[
        "os": "${umb.OS}",
        "architecture": "${umb.ARCH}",
        "provider": "${umb.PROVIDER}"
    ]]
    message_map.test = [
        "category": "functional",
        "namespace": "${umb.NAMESPACE}.brew-build",
        "type": "${umb.TESTSUITE}",
        "result": "${umb.TESTRESULT}"
    ]
    message_map.version = "1.1.14"

    message_content = JsonOutput.toJson(message_map)
    echo "${message_content}"
    def ret = sendCIMessage (
        providerName: 'Red Hat UMB',
        overrides: [topic: "VirtualTopic.eng.ci.${umb.CHANNEL}.brew-build.test.${message_type}"],
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
