@RestController
public class SecurityConfig {
  @CrossOrigin("*")
  @GetMapping("/x")
  public String x() { return "x"; }

  void configure(HttpSecurity http) throws Exception {
    http.csrf().disable()
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
  }
}
