// Synthetic SAST taint fixture (M8.15 S4) — no real code/secrets. Existing-pack (Java) taint-rule hits.
import java.sql.Connection;
import java.sql.Statement;
import javax.servlet.http.HttpServletRequest;

public class Vuln {
    // Direct (intra-procedural) JDBC SQL injection: fires with and without --taint-intrafile.
    void direct(HttpServletRequest req, Connection c) throws Exception {
        String user = req.getParameter("user");
        Statement st = c.createStatement();
        st.executeQuery("SELECT * FROM members WHERE handle = '" + user + "'");
    }

    // A same-file source helper; the sink below is tainted only through inter-procedural (intra-file) flow.
    String fromRequest(HttpServletRequest req) {
        return req.getParameter("account");
    }

    void viaHelper(HttpServletRequest req, Connection c) throws Exception {
        String account = fromRequest(req);
        Statement st = c.createStatement();
        st.executeQuery("DELETE FROM ledger WHERE account_no = '" + account + "'");
    }
}
