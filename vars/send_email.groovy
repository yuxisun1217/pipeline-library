def call(String subject, String body, String from, String to) {
    mail (
        body: "${body},"
        subject: "${subject}",
        from: "${from}",
        to: "${to}"
    )
}
