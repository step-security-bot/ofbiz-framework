#!/usr/bin/env sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Whatever, create anew
if [ -d "plugins" ]
    then
        rm -rf plugins
fi

# Get the branch used by the framework
git branch --show-current > temp.txt
branch=$(cat temp.txt)
rm temp.txt

git clone https://github.com/apache/ofbiz-plugins.git plugins
cd plugins

# By default the clone branch is trunk
if [ ! $branch = trunk ]
    then
        git switch "$branch"
fi

# remove .git, in this case it's big useless information
rm -rf .git
cd ..