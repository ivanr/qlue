#!/bin/bash
set -e

# Get the password

echo -n "GitHub Packages password: "
read -s password
echo

# Run Gradle to publish the package

export GITHUB_PACKAGES_USERNAME=ivanr
export GITHUB_PACKAGES_PASSWORD=$password

DIR=$(dirname "$0")
PWD=$(pwd)

cd $PWD/$DIR
./gradlew publish

cd $PWD
