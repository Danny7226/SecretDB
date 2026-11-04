# SecretDB
[SQLite backend, migration, inspection and delete operation](SQLITE.md)

A simple server that stores secrets on-disk with server-side encryption using tenant-specified private key;

For security, either use it within your localhost, or always use **https** when running this server on a remote desktop

*Update: I decided to disable traffic from non-localhost in [commit](https://github.com/Danny7226/SecretDB/commit/3619da12d29fb84cd48606e0a97b174902ec2c32) and made tomcat only listening for IP 127.0.0.1 on port. After this change, I recommend setting up a reverse proxy in front of this server, e.g [nginx](https://nginx.org/en/docs/)*

### Background
This project sets/spins up a servlet container service that stores encrypted secrets on disk.

For fun, it doesn't use Spring frameworks, but vanilla tomcat servlet container with self-registered servlets

### Key functional features in this service
* Get/List/PUT apis
* Store secrets on-disk encrypted
* Persistent logs to dedicated log files
* Scheduled daemon that backs up datafile once every day
* Scheduled daemon that cleans up application logs earlier than 2 days ago
* All files (data, logs, backup) are stored under `${system:user.home}/secret_db/` folder
  * In linux, it's `/home/duochai/secret_db`
  * In MacOs, it's `/Users/duochai/secret_db`

### Key technical features in this service
* Tomcat 10.x
* OpenJDK 21
* Compile-time IoC with Google Dagger
* Log4j2 2.24.3
* Daemon threads
* Java crypto AES & Java security

### Spin up server
If you don't have jdk21 installed
```
wget https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz

sudo tar jdk-21_linux-x64_bin.tar.gz -C /opt/
sudo tar -xvzf jdk-21_linux-x64_bin.tar.gz -C /opt/

export JAVA_HOME=/opt/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
```

Run below to spin up
```
mvn package

java -jar target/SecretDB-1.0-SNAPSHOT.jar $PORT_NUMBER
```

or 

```
./start_server.sh
```

### APIs
```
# to list
curl "http://localhost:8080/api/list/{tenant_id}" 

# to list and grep
curl "http://localhost:8080/api/list/{tenant_id}" | grep -i "name" 
curl -s "http://localhost:8080/api/list/{tenant_id}" | grep -i "name" 

# to get
curl -X POST http://localhost:8080/api/duochai -H "Content-Type: application/json" -d '{"key":"key1", "name":"name"}' 

# to write
curl -i -X PUT http://localhost:8080/api/duochai -H "Content-Type: application/json" -d '{"key":"key1", "name":"name", "value": "value"}'
```

### As a client
* This is a file named "client" at the root directory of this git repo.
* Look for it and move it outside of this git repo.
* Update `SECRET_DB_TENANT` and `SECRET_DB_PRIVATE_KEY`, then source file `source /path/to/your/file` at the end of your shell profile, e.g. `~/.zshrc`
* After which, use cmd below
```
sdb ls # list all secrects

sdb r "test record" # read/get a secret with private_key specified on file

sdb w "new record" "sec1:abc sec_a:bcsd" # write a new secret named "new record" with a value
```

### Debug
* `jar tf target/SecretDB-1.0-SNAPSHOT.jar` to check all classes packaged in jar

### Contribute
```
git remote add origin https://github.com/Danny7226/SecretDB.git
git branch -u origin/mainline master
git config --list

# (set local git config)
git config user.email "duochai9611@gmail.com"

# (push to remote [origin] branch [mainline])
git push origin HEAD:mainline
```
