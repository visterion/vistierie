# Common Development Commands

## Environment Setup
```bash
export JAVA_HOME=/usr/local/lib/jdk-25.0.2+10
export PATH=$JAVA_HOME/bin:$PATH
cd /root/vistierie/java-server
```

## Testing
```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw -Dtest=PriceTableTest test

# Run specific test method
./mvnw -Dtest=PriceTableTest#haikuPrice test
```

## Building
```bash
./mvnw clean package
```

## Git (from /root/vistierie repo root)
```bash
git status
git add java-server/
git commit -m "feat(slice1): message"
git log --oneline -10
```

## Running Application
(Not yet completed — will add after Task 11)
