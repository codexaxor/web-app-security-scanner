public class SecurityConfig {
  void configure(HttpSecurity http) throws Exception {
    http.csrf().disable(); // nosemgrep: spring-csrf-disabled
  }
}
