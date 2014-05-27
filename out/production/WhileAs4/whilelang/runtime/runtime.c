#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

typedef int64_t slot_t;

#define SLOT_SIZE 8

#define VOID_TAG 0
#define BOOL_TAG 1
#define CHAR_TAG 2
#define INT_TAG 3
#define REAL_TAG 4
#define STRING_TAG 5
#define RECORD_TAG 6
#define LIST_TAG 7

/**
 * Runtime support for While on X86.  Implemented in C for simplicity.
 */

int widthof(slot_t *type) {
 slot_t tag = *type;

  switch(tag) {
  case VOID_TAG:
    // void
    break;
  case BOOL_TAG:
  case CHAR_TAG: 
  case INT_TAG: 
  case REAL_TAG: 
  case STRING_TAG: 
    // bool
    return SLOT_SIZE;    
  case LIST_TAG: 
    {
      int i;
      int width = 0;
      // record
      slot_t nfields = *(++type);
      for(i=0;i!=nfields;++i) {
	slot_t fieldNameSize = *(++type);
	type = ((void *)type) + fieldNameSize + 1;
	width = width + widthof(type);
	// FIXME: this is clearly broken here, since we don't properly increment type.  Rather, we're assuming it's a single slot.
      }
      return width;
    }
  }
}

void internal_tostring(slot_t *item, slot_t *type, char* buf) {

  // NOTE: this function is not working properly yet.

  slot_t tag = *type;

  switch(tag) {
  case VOID_TAG:
    // void
    break;
  case BOOL_TAG:
    // bool
    if(*item == 0) {
      sprintf(buf,"false");
    } else {
      sprintf(buf,"true");
    }
    break;
  case CHAR_TAG: 
    {
      // char
      char tmp[2];
      tmp[0] = *item;
      tmp[1] = '\0';
      sprintf(buf,"%s",tmp);
      break;
    }
  case INT_TAG:
    // int
    sprintf(buf,"%d",*item);
    break;
  case REAL_TAG:
    // real
    sprintf(buf,"%g",*item);
    break;
  case STRING_TAG:
    // string
    sprintf(buf,"%s",item);
    break;
  case RECORD_TAG: 
    {
      int i;
      // record
      sprintf(buf,"{");
      buf += strlen(buf);
      slot_t nfields = *(++type);
      for(i=0;i!=nfields;++i) {
	if(i != 0) {
	  sprintf(buf,",");
	  buf += strlen(buf);
	}
	slot_t fieldNameSize = *(++type);
	sprintf(buf,"%s:",++type);
	buf += strlen(buf);
	type = ((void *)type) + fieldNameSize + 1;
	internal_tostring(item,type,buf);
	buf += strlen(buf);
	item = ((void *)item) + widthof(type);
	// FIXME: this is clearly broken here, because we don't increment type correctly.
      }
      sprintf(buf,"}");
    }
  }
}

void internal_print(slot_t *item, slot_t *type) {

  // NOTE: this function is not working properly yet.

  slot_t tag = *type;

  switch(tag) {
  case VOID_TAG:
    // void
    break;
  case BOOL_TAG:
    // bool
    if(*item == 0) {
      printf("false");
    } else {
      printf("true");
    }
    break;
  case CHAR_TAG: 
    {
      // char
      char tmp[2];
      tmp[0] = *item;
      tmp[1] = '\0';
      printf("%s",tmp);
      break;
    }
  case INT_TAG:
    // int
    printf("%d",*item);
    break;
  case REAL_TAG:
    // real
    printf("%g",*item);
    break;
  case STRING_TAG:
    // string
    printf("%s",item);
    break;
  case RECORD_TAG: 
    {
      int i;
      // record
      printf("{");
      slot_t nfields = *(++type);
      for(i=0;i!=nfields;++i) {
	if(i != 0) {
	  printf(",");
	}
	slot_t fieldNameSize = *(++type);
	printf("%s:",++type);
	type = ((void *)type) + fieldNameSize + 1;
	internal_print(item,type);
	item = ((void *)item) + widthof(type);
	// FIXME: this is clearly broken here, because we don't increment type correctly.
      }
      printf("}");
    }
  }
}

void print(slot_t item, slot_t *type) {
  slot_t tag = *type;
  switch(tag) {
  case 0:
  case 1:
  case 2:
  case 3:
  case 4:
    internal_print(&item,type);    
    break;
  default:
    internal_print((slot_t*)item,type); 
  }
  printf("\n");
}

char *str_append(char *lhs, char *rhs) {
  char *result = malloc(1 + strlen(lhs) + strlen(rhs));
  strcpy(result,lhs);
  return strcat(lhs,rhs);
}

char *str_left_append(char *lhs, slot_t rhs, slot_t *type) {
  int lhsLen = strlen(lhs);
  slot_t tag = *type;
  char buf[1024];
  strcat(buf,lhs);

  switch(tag) {
  case 0:
  case 1:
  case 2:
  case 3:
  case 4:
    internal_tostring(&rhs,type,buf + lhsLen);
    break;
  default:
    internal_tostring((slot_t*)rhs,type,buf + lhsLen);
  }
  char *result = malloc(1 + strlen(buf));
  strcpy(result, buf);
  return result;
}

char *str_right_append(slot_t lhs, char *rhs, slot_t *type) {
  return "blah blah";
}



