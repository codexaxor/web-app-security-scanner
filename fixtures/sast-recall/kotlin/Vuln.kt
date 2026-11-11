import java.security.MessageDigest

fun weak() {
    val md = MessageDigest.getInstance("MD5")
    md.digest("x".toByteArray())
}

fun run(user: String) {
    Runtime.getRuntime().exec("echo " + user)
}
