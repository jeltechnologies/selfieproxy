package boringproxy

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
)

// saveJson writes via a temp file + rename rather than truncating filePath
// in place, so a concurrent reader (another process, or a server restarting
// mid-write) always sees either the previous complete file or the new one --
// never a truncated/empty one. See NewDatabase in database.go, which treats
// any non-ENOENT read failure as fatal precisely because a torn read here
// must never be mistaken for "no database yet".
func saveJson(data interface{}, filePath string) error {
	jsonStr, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return errors.New("Error serializing JSON")
	}

	tmpPath := fmt.Sprintf("%s.tmp-%d", filePath, os.Getpid())
	if err := ioutil.WriteFile(tmpPath, jsonStr, 0644); err != nil {
		return errors.New("Error saving JSON")
	}

	if err := os.Rename(tmpPath, filePath); err != nil {
		os.Remove(tmpPath)
		return errors.New("Error saving JSON")
	}

	return nil
}

// Looks for auth token in query string, then headers, then cookies
func extractToken(tokenName string, r *http.Request) (string, error) {

	query := r.URL.Query()

	queryToken := query.Get(tokenName)
	if queryToken != "" {
		return queryToken, nil
	}

	tokenHeader := r.Header.Get(tokenName)
	if tokenHeader != "" {
		return tokenHeader, nil
	}

	authHeader := r.Header.Get("Authorization")
	if authHeader != "" {
		tokenHeader := strings.Split(authHeader, " ")[1]
		return tokenHeader, nil
	}

	tokenCookie, err := r.Cookie(tokenName)
	if err == nil {
		return tokenCookie.Value, nil
	}

	return "", errors.New("No token found")
}

const chars string = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

func genRandomCode(length int) (string, error) {
	id := ""
	for i := 0; i < length; i++ {
		randIndex, err := rand.Int(rand.Reader, big.NewInt(int64(len(chars))))
		if err != nil {
			return "", err
		}
		id += string(chars[randIndex.Int64()])
	}
	return id, nil
}

func randomOpenPort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}

	addrParts := strings.Split(listener.Addr().String(), ":")
	port, err := strconv.Atoi(addrParts[len(addrParts)-1])
	if err != nil {
		return 0, err
	}

	listener.Close()

	return port, nil
}

func stringInArray(value string, array []string) bool {
	for _, item := range array {
		if item == value {
			return true
		}
	}
	return false
}
