// send message to google chat space
// eg. google-chat-notifier('<gchat webhook url>', '<message>')
def call(String webhook_url, String message) {
    googlechatnotification url: webhook_url, message: message, messageFormat: 'simple', sameThreadNotification: 'false'
}
