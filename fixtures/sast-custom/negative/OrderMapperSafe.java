import org.apache.ibatis.annotations.Select;

public interface OrderMapperSafe {
    @Select("SELECT * FROM orders WHERE id = #{id}")
    Object find(String id);
}
