import org.apache.ibatis.annotations.Select;

public interface OrderMapper {
    @Select("SELECT * FROM orders WHERE id = '${id}' ORDER BY ${sort}")
    Object find(String id, String sort);
}
