#!/bin/bash

# Remove all Hilt-related imports and annotations from Kotlin files
find app/src/main/java -name "*.kt" -type f -exec sed -i \
  -e '/^import javax\.inject\./d' \
  -e '/^import dagger\./d' \
  -e '/@Inject/d' \
  -e '/@Singleton/d' \
  -e '/@HiltViewModel/d' \
  -e '/@AndroidEntryPoint/d' \
  -e '/@ApplicationContext/d' \
  {} \;

echo "Hilt references removed"
