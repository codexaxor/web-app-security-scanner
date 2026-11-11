import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// Synthetic fixture (M8.15 review B1): a taint finding whose dataflow_trace would carry this source line,
// including the obviously fake canary literal below, if the sanitize let extra.dataflow_trace through.
public class TraceCanary extends HttpServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        request.getSession().setAttribute(request.getParameter("k"), "L3AKtraceCanary3131_fake");
    }
}
