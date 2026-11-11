import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.owasp.encoder.Encode;

public class ServletSafe extends HttpServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // constant key AND constant value: no untrusted data crosses into the session
        request.getSession().setAttribute("constantKey", "constantValue");
        // request value is HTML-encoded before being written
        response.getWriter().print(Encode.forHtml(request.getParameter("name")));
    }
}
