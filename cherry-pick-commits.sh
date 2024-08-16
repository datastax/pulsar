#!/bin/bash
DEST_BRANCH="3.1_ds"
SRC_BRANCH="apache/branch-3.0"
OUTPUT_FILE="/tmp/commit_analysis.csv"
# Print CSV headers
echo "Commit Timestamp,Commit Id,Required,Author,Commit Message,Cherry-picked/similarcommits" > "$OUTPUT_FILE"
for commit in $(git rev-list $SRC_BRANCH ^$DEST_BRANCH | tac) # Reverse order
do
    ts_id=$(git show -s --format="%cI,%H" $commit)
    first_line_raw=$(git show -s --format=%s $commit)
    # Remove single or double `[text]` prefixes at the start of the message
    first_line=$(echo "$first_line_raw" | sed -E 's/^(\[[^]]+\][[:space:]]*)+//')
    # Escape special characters for grep
    first_line_escaped=$(echo "$first_line" | sed -E 's/[]\/$*.^|[]/\\&/g')
    author_email_name=$(git show -s --format="%ae,%an" $commit)
    cherries=$(git show -s --format="%B" $commit | grep "cherry picked from commit" | awk -F'cherry picked from commit |)' '{print $2}')
    cherries_commas=$(echo "$cherries" | tr '\n' ',')
    # --- 1. Check if the commit itself is already in the destination branch ---
    if git branch --contains "$commit" | grep -q "$DEST_BRANCH"; then
        echo "$ts_id,no,\"$author_email_name\",\"$first_line_raw\",\"$cherries_commas\"" >> "$OUTPUT_FILE"
        continue
    fi
    # --- 2. Check if the commit has been cherry-picked ---
    is_commit_in_dest_branch=0
    for cherry in $cherries
    do
        if git branch --contains "$cherry" | grep -q "$DEST_BRANCH"; then
            is_commit_in_dest_branch=1
            break
        fi
    done
    if [ $is_commit_in_dest_branch -eq 1 ]; then
        echo "$ts_id,no,\"$author_email_name\",\"$first_line_raw\",\"$cherries_commas\"" >> "$OUTPUT_FILE"
        continue
    fi
    # --- 3. Ignore release and version bump commits ---
    if echo "$first_line" | grep -Eiq "^Release [[:digit:].]*$|^Bump version to"; then
        continue
    fi
    if echo "$first_line" | grep -Eiq "^Start release [[:digit:].]*-SNAPSHOT$"; then
        continue
    fi
    # --- 4. Handle revert commits correctly ---
    pr_id=$(echo "$first_line_raw" | awk -F'[()]' '{gsub(/[^0-9#]/,"",$2); print $2}')
    if [ -n "$pr_id" ] && echo "$first_line_raw" | grep -iqE 'revert' && \
        [ "$(git log "$SRC_BRANCH" | grep -c "$pr_id")" -ne 1 ] && \
        [ $(( $(git log "$SRC_BRANCH" | grep -c "$pr_id") % 2 )) -eq 1 ]; then
        continue
    fi
    # --- 5. Check if a commit with the same message & author exists in DEST_BRANCH ---
    author_email=${author_email_name%,*}
    author_name=${author_email_name#*,}
    # Remove `[text]` and `[text][text]` from commit messages while searching in DEST_BRANCH
    similar_commits=$(git log "$DEST_BRANCH" --grep="$first_line_escaped"--author="$author_name" --author="$author_email" --pretty=format:"%H")
    similar_commits_commas=$(echo "$similar_commits" | tr '\n' ',')
    if [ -n "$similar_commits" ]; then
        echo "$ts_id,no,\"$author_email_name\",\"$first_line_raw\",\"$cherries_commas$similar_commits_commas\"" >> "$OUTPUT_FILE"
        continue
    fi
    # --- 6. If commit is truly missing, mark it as "yes" ---
    echo "$ts_id,yes,\"$author_email_name\",\"$first_line_raw\",\"$cherries_commas$similar_commits_commas\"" >> "$OUTPUT_FILE"
done
echo "Analysis complete. Output saved to $OUTPUT_FILE."

