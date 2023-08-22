import java.net.URLEncoder

// Send a message to your phone (Use Bark app)
def call(String title, String body, String barkkey) {
    title_encode = URLEncoder.encode(title, "UTF-8")
    body_encode = URLEncoder.encode(body, "UTF-8")
    sh "curl https://api.day.app/${barkkey}/${title_encode}/${body_encode}||true"
}
