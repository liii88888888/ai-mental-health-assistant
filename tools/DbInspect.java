import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

/**
 * 只读工具：打印某张表的列和索引，还可以顺带跑一条 SQL。
 *
 * 用法：
 *   java -cp <mysql-connector-j.jar> tools/DbInspect.java <表名> ["select ..."]
 *
 * 数据库密码从环境变量 DB_PASSWORD 读（默认空），仓库里不写任何明文密码。
 */
public class DbInspect {

    public static void main(String[] args) throws Exception {
        String table = (args.length > 0) ? args[0] : "emotion_diary";
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String dbName = System.getenv().getOrDefault("DB_NAME", "mental_health_assistant");
        String user = System.getenv().getOrDefault("DB_USERNAME", "root");
        String pass = System.getenv().getOrDefault("DB_PASSWORD", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

        try (Connection c = DriverManager.getConnection(url, user, pass)) {

            System.out.println("========== " + table + " 的列 ==========");
            try (ResultSet rs = c.createStatement().executeQuery("SHOW FULL COLUMNS FROM " + table)) {
                while (rs.next()) {
                    System.out.printf("%-26s %-14s null=%-4s key=%-4s extra=%s%n",
                            rs.getString("Field"),
                            rs.getString("Type"),
                            rs.getString("Null"),
                            rs.getString("Key"),
                            rs.getString("Extra"));
                }
            }

            System.out.println();
            System.out.println("========== " + table + " 的索引 ==========");
            try (ResultSet rs = c.createStatement().executeQuery("SHOW INDEX FROM " + table)) {
                while (rs.next()) {
                    System.out.printf("%-22s seq=%-2s col=%-24s unique=%s%n",
                            rs.getString("Key_name"),
                            rs.getString("Seq_in_index"),
                            rs.getString("Column_name"),
                            rs.getString("Non_unique").equals("0") ? "YES" : "no");
                }
            }

            // 第二个参数可以传一条只读 SQL（只允许 SELECT），用来快速看数据
            if (args.length > 1) {
                String sql = args[1].trim();
                String lower = sql.toLowerCase();

                if (lower.startsWith("select")) {
                    System.out.println();
                    System.out.println("========== 查询结果 ==========");
                    try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                        int cols = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 1; i <= cols; i++) {
                                sb.append(rs.getMetaData().getColumnLabel(i)).append("=")
                                  .append(rs.getString(i)).append("  ");
                            }
                            System.out.println(sb.toString().trim());
                        }
                    }
                }
                else if (lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete")) {
                    System.out.println();
                    System.out.println("========== 执行写操作 ==========");
                    int rows = c.createStatement().executeUpdate(sql);
                    System.out.println("影响行数 = " + rows);
                }
                else {
                    System.out.println("\n只支持 SELECT / INSERT / UPDATE / DELETE");
                }
            }

        }
    }
}
