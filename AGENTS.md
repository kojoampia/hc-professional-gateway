# Project Overview

## Code Quality and Style

- Follow SOLID principles and clean code practices.
- Use consistent naming conventions and code formatting.
- Implement comprehensive unit and integration tests using JUnit 5 and Mockito.
- Ensure proper documentation of code and APIs using JavaDoc and Swagger/OpenAPI.
- No null pointer deferences; use Optional where applicable.
- Handle exceptions gracefully and provide meaningful error messages.
- Use Lombok for boilerplate code reduction (getters, setters, constructors).
- Adhere to RESTful API design principles for all endpoints.
- Use Liquibase for database migrations and version control.
- Implement logging using SLF4J and Logback for all critical operations and exceptions.
- Follow resource leak prevention best practices, especially in file handling and database connections.

## Architecture and Design

- Use a layered architecture (Controller, Service, Repository) for separation of concerns.
- Implement domain-driven design principles for modeling the healthcare workforce and related entities.
- Dependency injection should be used for all services and repositories to promote testability and maintainability.
- Use Kafka for asynchronous communication between services, especially for telemetry data and alerts.
- Integrate MinIO for document storage, ensuring secure and efficient handling of professional and vendor documents
- No static initialization blocks; use dependency injection for all configurations and services.
- Implement a robust error handling mechanism using `@ControllerAdvice` to return RFC 7807 compliant error responses for all exceptions.
- Immutable objects for data transfer objects (DTOs) and domain models where appropriate to ensure thread safety and maintainability.

## Security Considerations

- Implement authentication and authorization using Spring Security, with role-based access control for all stakeholders (Professionals, Vendors, Admins).
- Ensure all sensitive data (e.g., personal information, documents) is encrypted at rest and in transit.
- Use secure password storage practices (e.g., bcrypt) for any user credentials.
- Implement input validation and sanitization to prevent common vulnerabilities such as SQL injection and cross-site scripting (XSS).
- Ensure proper CORS configuration for the Angular frontend to securely interact with the backend APIs.
- Regularly update dependencies to mitigate known security vulnerabilities.
- Implement rate limiting and monitoring to prevent abuse of the APIs and ensure system stability under load.
- Use HTTPS for all communications between the frontend and backend services to ensure data confidentiality and integrity.
- Ensure logs do not contain sensitive data or PII information and are properly secured to prevent unauthorized access.
- Implement comprehensive testing for security vulnerabilities, including penetration testing and vulnerability scanning as part of the development lifecycle.
- Ensure compliance with relevant data protection regulations (e.g., GDPR, HIPAA) in the handling of personal and health-related data.
- Use secure coding practices and conduct regular code reviews to identify and mitigate potential security issues early in the development process.
- Implement a secure document upload mechanism that validates file types, sizes, and content to prevent malicious uploads and ensure the integrity of stored documents.

## Performance Optimization

- Use pagination and filtering for API endpoints that return large datasets to improve response times and reduce memory usage.
- Implement caching strategies (e.g., using Spring Cache) for frequently accessed data to reduce database load and improve response times.
- Optimize database queries using indexing and proper query design to ensure efficient data retrieval and manipulation.
- Use asynchronous processing for long-running tasks (e.g., document uploads, complex matching algorithms) to improve responsiveness and user experience.
- Monitor application performance using tools like Spring Boot Actuator and implement necessary optimizations based on observed metrics and bottlenecks.
- Implement connection pooling for database connections to improve performance and resource management.
- Use efficient data structures and algorithms in the implementation of the matching service and threshold engine to ensure optimal performance under load.
- Regularly profile the application to identify and address performance bottlenecks, especially in critical paths such as the matching service and Kafka consumer.
- Ensure that the application can scale horizontally by designing stateless services and using appropriate load balancing strategies to handle increased traffic and workload effectively.

## Technology Stack

- Java 26
- Spring Boot 4
- Spring Web, Spring Data JPA, Spring Security, Spring Kafka, Spring Cloud AWS
- PostgreSQL
- MinIO for document storage
- Angular 19+ for the frontend
- Docker and Docker Compose for containerization
- Liquibase for database migrations
- JUnit 5 and Mockito for testing
- SLF4J and Logback for logging
- Swagger/OpenAPI for API documentation
- Maven for build and dependency management
- NPM for frontend package management
- Nginx for serving the Angular frontend in production
- Testcontainers for integration testing with PostgreSQL and MinIO
- Git for version control and collaboration
- GitHub Actions for CI/CD pipelines to automate testing and deployment processes.
