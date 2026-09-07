public class Employee {
    // It's private fields, why do we need final keyword here? 
    // Because we want to make sure that these fields are immutable once they are set in the constructor. 
    // This means that once an Employee object is created, its name, age, and department cannot be changed. 
    // This is a good practice for creating immutable objects, which can help prevent bugs and make the code easier to reason about.
    private final String name;
    private final int age;
    private final String department;

    public Employee(String name, int age, String department) {
        this.name = name;
        this.age = age;
        this.department = department;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public String getDepartment() {
        return department;
    }
}
