import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ServletVulns extends HttpServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // servlet-trust-boundary-session: untrusted request value used as a session key
        request.getSession().setAttribute(request.getParameter("key"), "v");
        // servlet-reflected-xss-inline: request value written straight to the response
        response.getWriter().print(request.getParameter("name"));
    }
}
