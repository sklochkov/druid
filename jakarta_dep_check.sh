mvn dependency:tree | grep javax | grep -v test | head -20

# Check critical extensions
for ext in pac4j ranger kerberos; do
    echo "=== $ext ==="
    mvn dependency:tree -pl extensions-core/*$ext* 2>/dev/null | grep javax || echo "Not found"
done
