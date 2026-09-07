public class Main {
    public static void main(String[] args) {
        Employee employee1 = new Employee("Alice", 30, "HR");
        Employee employee2 = new Employee("Bob", 25, "IT");
        Employee employee3 = new Employee("Charlie", 35, "Finance");

        Specification<Employee> isOlderThan30 = EmployeeSpecifications.isOlderThan(30);
        Specification<Employee> isInITDepartment = EmployeeSpecifications.isInDepartment("IT");

        System.out.println("Employee 1 is older than 30: " + isOlderThan30.isSatisfiedBy(employee1));
        System.out.println("Employee 2 is in IT department: " + isInITDepartment.isSatisfiedBy(employee2));
        System.out.println("Employee 3 is older than 30 and in IT department: " +
                isOlderThan30.and(isInITDepartment).isSatisfiedBy(employee3));
    }
}
