import java.sql.*;
import javax.persistence.*;

public class SqlAndCmd {
    Connection conn;

    public ResultSet jdbc(String id) throws Exception {
        Statement st = conn.createStatement();
        return st.executeQuery("SELECT * FROM users WHERE id = '" + id + "'");
    }

    public Query jpa(EntityManager em, String name) {
        String sql = "SELECT u FROM User u WHERE u.name = '" + name + "'";
        return em.createQuery(sql);
    }

    public String fmt(String id) {
        return String.format("SELECT * FROM t WHERE id = '%s'", id);
    }

    public void cmd(String user) throws Exception {
        new ProcessBuilder("sh", "-c", "ls " + user).start();
    }
}
