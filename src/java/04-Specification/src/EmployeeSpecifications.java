// What are the benefits of using the Specification pattern for Employee filtering?
// The Specification pattern allows for the creation of reusable and composable business rules that can be applied to filter Employee objects.
// The best practices here to define fine grained specifications 
// that can be combined using logical operators (AND, OR, NOT) 
// to create complex filtering criteria without modifying the existing codebase.
public class EmployeeSpecifications {
    public static Specification<Employee> hasName(String name) {
        // This lambda expression to define an anonymous function 
        // that implements the Specification interface (isSatisfiedBy) for Employee objects.
        // the name should be final to ensure that it cannot be modified within the lambda expression
        return employee -> employee.getName().equals(name);
    }    

    public static Specification<Employee> isOlderThan(int age) {
        return employee -> employee.getAge() > age;
    }

    public static Specification<Employee> isInDepartment(String department) {
        return employee -> employee.getDepartment().equals(department);
    }
}